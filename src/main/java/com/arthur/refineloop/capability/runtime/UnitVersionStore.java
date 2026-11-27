package com.arthur.refineloop.capability.runtime;

import com.arthur.refineloop.capability.spi.UnitType;
import com.arthur.refineloop.common.util.DateTimes;
import com.arthur.refineloop.data.entity.UnitVersionEntity;
import com.arthur.refineloop.data.mapper.UnitVersionMapper;
import lombok.extern.slf4j.Slf4j;

import java.time.Clock;

/**
 * 单元版本登记：启动期把 {@link UnitRegistry} 中的单元同步到 {@code unit_version}。
 *
 * <p>只增不改；已存在行不会被改写，仅校正主版本 prompt_path，保证重复启动幂等且不清空人工 params。
 */
@Slf4j
public class UnitVersionStore {

    private static final String STATUS_ACTIVE = "ACTIVE";

    private final UnitVersionMapper mapper;
    private final Clock clock;

    public UnitVersionStore(UnitVersionMapper mapper, Clock clock) {
        this.mapper = mapper;
        this.clock = clock;
    }

    /** 同步一条注册单元；返回落库后的实体。 */
    public UnitVersionEntity sync(UnitRegistry.Entry entry) {
        UnitVersionEntity entity = new UnitVersionEntity();
        entity.setUnitId(entry.unitId());
        entity.setVersion(entry.version());
        entity.setChangeNote("auto-registered at startup");
        entity.setPromptPath(promptPath(entry));
        entity.setStatus(STATUS_ACTIVE);
        entity.setCreatedAt(DateTimes.now(clock));
        entity.setActivatedAt(DateTimes.now(clock));
        mapper.upsert(entity);
        log.info("[capability] unit_version 同步 {} {}", entry.unitId(), entry.version());
        return entity;
    }

    public UnitVersionEntity find(String unitId, String version) {
        return mapper.selectByUnitAndVersion(unitId, version);
    }

    private String promptPath(UnitRegistry.Entry entry) {
        if (entry.type() != UnitType.INTERPRET) {
            return null;
        }
        return "prompts/" + entry.unitId() + "/" + majorVersion(entry.version()) + ".md";
    }

    private String majorVersion(String version) {
        String withoutPrefix = version.substring(1);
        int dot = withoutPrefix.indexOf('.');
        return "v" + (dot < 0 ? withoutPrefix : withoutPrefix.substring(0, dot));
    }
}
