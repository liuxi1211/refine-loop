package com.arthur.refineloop.capability.runtime;

import com.arthur.refineloop.capability.spi.FactDataStatus;
import com.arthur.refineloop.capability.spi.FactResult;
import com.arthur.refineloop.common.ApiException;
import com.arthur.refineloop.common.util.DateTimes;
import com.arthur.refineloop.common.util.JsonUtils;
import com.arthur.refineloop.data.entity.UnitFactEntity;
import com.arthur.refineloop.data.mapper.UnitFactMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Clock;
import java.util.ArrayList;
import java.util.List;

/**
 * 事实存取：unit_fact 的唯一读写入口（按 run 追加、不可覆盖）。
 *
 * <p>幂等键 {@code run_id + unit_id + unit_version + ts_code + trade_date}。同一 run 重复提交相同事实为 no-op，
 * 内容不同则拒绝；不同 run 产生新行，保证旧判断/旧报告的溯源证据不会被重跑改写。
 * 批量保存在一个短事务内完成，不允许部分成功；事务内无 HTTP/LLM/文件 IO。
 */
@Slf4j
public class FactStore {

    private final UnitFactMapper mapper;
    private final TransactionTemplate transactionTemplate;
    private final Clock clock;

    public FactStore(UnitFactMapper mapper, TransactionTemplate transactionTemplate, Clock clock) {
        this.mapper = mapper;
        this.transactionTemplate = transactionTemplate;
        this.clock = clock;
    }

    /** 批量追加某次运行的事实；返回本次新插入行数（相同事实重复提交不重复计数）。 */
    public int saveAll(String runId, String tradeDate, List<FactResult> facts) {
        if (facts == null || facts.isEmpty()) {
            return 0;
        }
        requireRunId(runId);
        if (tradeDate == null || !tradeDate.matches("\\d{8}")) {
            throw new IllegalArgumentException("tradeDate 必须为 yyyyMMdd：" + tradeDate);
        }
        Integer saved = transactionTemplate.execute(status -> {
            int count = 0;
            for (FactResult fact : facts) {
                count += saveOne(runId, tradeDate, fact);
            }
            return count;
        });
        int result = saved == null ? 0 : saved;
        log.debug("[capability] unit_fact 本次新增 {} 条 runId={} tradeDate={}", result, runId, tradeDate);
        return result;
    }

    public int save(String runId, String tradeDate, FactResult fact) {
        return saveAll(runId, tradeDate, List.of(fact));
    }

    public List<FactResult> findByUnit(String runId, String unitId, String unitVersion, String tradeDate) {
        requireRunId(runId);
        return toFacts(mapper.selectByUnitAndDate(runId, unitId, unitVersion, tradeDate));
    }

    public List<FactResult> findByTradeDateAndTsCode(String runId, String tradeDate, String tsCode) {
        requireRunId(runId);
        return toFacts(mapper.selectByTradeDateAndTsCode(runId, tradeDate, tsCode));
    }

    private int saveOne(String runId, String tradeDate, FactResult fact) {
        UnitFactEntity entity = toEntity(runId, tradeDate, fact);
        int inserted = mapper.insertIgnore(entity);
        if (inserted > 0) {
            return 1;
        }
        UnitFactEntity existing = mapper.selectByKey(runId, fact.unitId(), fact.unitVersion(),
                fact.tsCode(), tradeDate);
        if (!sameFact(existing, entity)) {
            throw ApiException.conflict("同一 run 的 unit_fact 内容发生变化，拒绝覆盖：runId=" + runId
                    + ", unit=" + fact.unitId() + ", stock=" + fact.tsCode());
        }
        return 0;
    }

    private boolean sameFact(UnitFactEntity existing, UnitFactEntity incoming) {
        return existing != null
                && existing.getSchemaVersion().equals(incoming.getSchemaVersion())
                && existing.getDataStatus().equals(incoming.getDataStatus())
                && JsonUtils.toJson(JsonUtils.parseMap(existing.getPayload()))
                        .equals(JsonUtils.toJson(JsonUtils.parseMap(incoming.getPayload())))
                && JsonUtils.toJson(JsonUtils.parseList(existing.getNotes(), String.class))
                        .equals(JsonUtils.toJson(JsonUtils.parseList(incoming.getNotes(), String.class)));
    }

    private UnitFactEntity toEntity(String runId, String tradeDate, FactResult fact) {
        UnitFactEntity entity = new UnitFactEntity();
        entity.setRunId(runId);
        entity.setUnitId(fact.unitId());
        entity.setUnitVersion(fact.unitVersion());
        entity.setSchemaVersion(fact.schemaVersion());
        entity.setTsCode(fact.tsCode());
        entity.setTradeDate(tradeDate);
        entity.setPayload(JsonUtils.toJson(fact.payload()));
        entity.setDataStatus(fact.dataStatus().name());
        entity.setNotes(JsonUtils.toJson(fact.notes()));
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
                    entity.getSchemaVersion(),
                    FactDataStatus.valueOf(entity.getDataStatus()),
                    JsonUtils.parseMap(entity.getPayload()),
                    JsonUtils.parseList(entity.getNotes(), String.class)));
        }
        return List.copyOf(facts);
    }

    private void requireRunId(String runId) {
        if (runId == null || runId.isBlank()) {
            throw new IllegalArgumentException("runId 不能为空：事实必须按运行隔离，禁止覆盖历史证据");
        }
    }
}
