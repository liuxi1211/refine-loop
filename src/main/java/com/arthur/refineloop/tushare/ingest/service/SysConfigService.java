package com.arthur.refineloop.tushare.ingest.service;

import com.arthur.refineloop.data.entity.SysConfigEntity;
import com.arthur.refineloop.data.mapper.SysConfigMapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * sys_config 读写（运维开关留痕），数据访问走 MyBatis-Plus Mapper。
 *
 * <p>本批次用于「每日增量开关」：key = {@code ingest.daily.<table>}，value = true/false；
 * 写入使用 SQLite {@code ON CONFLICT(config_key) DO UPDATE}（一条语句原子 upsert）。
 * 读失败（表不存在/库不可用）时回退默认值，避免启动期开关读取阻塞应用。
 */
@Component
public class SysConfigService {

    public static final String DAILY_TOGGLE_PREFIX = "ingest.daily.";

    private final SysConfigMapper mapper;
    private final Clock clock;

    public SysConfigService(SysConfigMapper mapper, Clock clock) {
        this.mapper = mapper;
        this.clock = clock;
    }

    public Optional<String> get(String key) {
        SysConfigEntity entity = mapper.selectOne(Wrappers.<SysConfigEntity>lambdaQuery()
                .eq(SysConfigEntity::getConfigKey, key)
                .last("LIMIT 1"));
        return Optional.ofNullable(entity).map(SysConfigEntity::getConfigValue);
    }

    public boolean getBoolean(String key, boolean defaultValue) {
        try {
            return get(key).map(Boolean::parseBoolean).orElse(defaultValue);
        } catch (Exception e) {
            // 无库/表缺失时回退默认值（启动期开关读取不阻塞应用）
            return defaultValue;
        }
    }

    public Map<String, String> getAllByPrefix(String prefix) {
        Map<String, String> result = new HashMap<>();
        try {
            List<SysConfigEntity> entities = mapper.selectList(Wrappers.<SysConfigEntity>lambdaQuery()
                    .likeRight(SysConfigEntity::getConfigKey, prefix));
            for (SysConfigEntity entity : entities) {
                result.put(entity.getConfigKey(), entity.getConfigValue());
            }
        } catch (Exception e) {
            // 无库时返回空（调用方回退默认开关）
        }
        return result;
    }

    /** 写入/更新配置并更新 remark 留痕（幂等 upsert）。 */
    public void set(String key, String value, String remark) {
        SysConfigEntity entity = new SysConfigEntity();
        entity.setConfigKey(key);
        entity.setConfigValue(value);
        entity.setRemark(remark);
        entity.setUpdatedAt(LocalDateTime.now(clock));
        mapper.upsert(entity);
    }

    /** 某表的每日增量开关（sys_config 覆盖注册表默认值）。 */
    public boolean dailyEnabled(String tableName, boolean registryDefault) {
        if (!registryDefault) {
            return false;
        }
        return getBoolean(DAILY_TOGGLE_PREFIX + tableName, true);
    }
}
