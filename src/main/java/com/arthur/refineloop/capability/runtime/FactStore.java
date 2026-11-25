package com.arthur.refineloop.capability.runtime;

import com.arthur.refineloop.capability.spi.FactDataStatus;
import com.arthur.refineloop.capability.spi.FactResult;
import com.arthur.refineloop.common.util.DateTimes;
import com.arthur.refineloop.common.util.JsonUtils;
import com.arthur.refineloop.data.entity.UnitFactEntity;
import com.arthur.refineloop.data.mapper.UnitFactMapper;
import lombok.extern.slf4j.Slf4j;

import java.time.Clock;
import java.util.ArrayList;
import java.util.List;

/**
 * 事实存取：unit_fact 的唯一读写入口。
 *
 * <p>幂等写入（unit_id + unit_version + ts_code + trade_date）；同一交易日重复执行只覆盖 payload/状态。
 * 单元只通过本类读写事实，禁止直接触达 Mapper。
 */
@Slf4j
public class FactStore {

    private final UnitFactMapper mapper;
    private final Clock clock;

    public FactStore(UnitFactMapper mapper, Clock clock) {
        this.mapper = mapper;
        this.clock = clock;
    }

    /**
     * 批量保存某交易日的事实；单条失败即抛出，由上层决定重试/降级。
     */
    public int saveAll(String tradeDate, List<FactResult> facts) {
        if (facts == null || facts.isEmpty()) {
            return 0;
        }
        if (tradeDate == null || !tradeDate.matches("\\d{8}")) {
            throw new IllegalArgumentException("tradeDate 必须为 yyyyMMdd：" + tradeDate);
        }
        int saved = 0;
        for (FactResult fact : facts) {
            mapper.upsert(toEntity(tradeDate, fact));
            saved++;
        }
        log.debug("[capability] unit_fact 写入 {} 条 tradeDate={}", saved, tradeDate);
        return saved;
    }

    public int save(String tradeDate, FactResult fact) {
        return saveAll(tradeDate, List.of(fact));
    }

    /** 按单元 + 版本 + 交易日读取事实。 */
    public List<FactResult> findByUnit(String unitId, String unitVersion, String tradeDate) {
        return toFacts(mapper.selectByUnitAndDate(unitId, unitVersion, tradeDate));
    }

    /** 读取某交易日某标的的全部事实（S3 装配 FactBundle 用）。 */
    public List<FactResult> findByTradeDateAndTsCode(String tradeDate, String tsCode) {
        return toFacts(mapper.selectByTradeDateAndTsCode(tradeDate, tsCode));
    }

    private UnitFactEntity toEntity(String tradeDate, FactResult fact) {
        UnitFactEntity entity = new UnitFactEntity();
        entity.setUnitId(fact.unitId());
        entity.setUnitVersion(fact.unitVersion());
        entity.setTsCode(fact.tsCode());
        entity.setTradeDate(tradeDate);
        entity.setPayload(JsonUtils.toJson(fact.payload()));
        entity.setDataStatus(fact.dataStatus().name());
        entity.setCreatedAt(DateTimes.now(clock));
        return entity;
    }

    private List<FactResult> toFacts(List<UnitFactEntity> entities) {
        if (entities == null || entities.isEmpty()) {
            return List.of();
        }
        List<FactResult> facts = new ArrayList<>(entities.size());
        for (UnitFactEntity entity : entities) {
            facts.add(new FactResult(
                    entity.getTsCode(),
                    entity.getUnitId(),
                    entity.getUnitVersion(),
                    entity.getUnitVersion(),
                    FactDataStatus.valueOf(entity.getDataStatus()),
                    JsonUtils.parseMap(entity.getPayload()),
                    List.of()));
        }
        return List.copyOf(facts);
    }
}
