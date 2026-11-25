package com.arthur.refineloop.data;

import com.arthur.refineloop.data.batch.MyBatisBatchExecutor;
import com.arthur.refineloop.data.entity.IngestLogEntity;
import com.arthur.refineloop.data.entity.QualityCheckLogEntity;
import com.arthur.refineloop.data.entity.SysConfigEntity;
import com.arthur.refineloop.data.mapper.IngestLogMapper;
import com.arthur.refineloop.data.mapper.QualityCheckLogMapper;
import com.arthur.refineloop.data.mapper.RateLimitCounterMapper;
import com.arthur.refineloop.data.mapper.SysConfigMapper;
import com.arthur.refineloop.data.mapper.TushareTableMapper;
import com.arthur.refineloop.support.SchemaTestSupport;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * ORM 层（MyBatis-Plus / MyBatis）基础能力验收。
 *
 * <p>验证四件事：
 * <ol>
 *   <li>{@code LocalDateTime} 自定义类型处理器：实体写入→读出值完全一致（SQLite 文本时间戳）；</li>
 *   <li>{@code ON CONFLICT} 注解 upsert：{@code sys_config} 重复写同 key 只有一行且值更新；</li>
 *   <li>原子累加：{@code rate_limit_counter.increment} 多次调用累加在同一行；</li>
 *   <li>BATCH 执行器：动态表批量写入的行数与内容正确，且批大小可控。</li>
 * </ol>
 */
class DataAccessLayerTest {

    private JdbcTemplate jdbc;
    private SysConfigMapper sysConfigMapper;
    private RateLimitCounterMapper rateLimitCounterMapper;
    private IngestLogMapper ingestLogMapper;
    private QualityCheckLogMapper qualityCheckLogMapper;
    private MyBatisBatchExecutor batchExecutor;

    @BeforeEach
    void setUp() {
        jdbc = SchemaTestSupport.newJdbcTemplate();
        sysConfigMapper = SchemaTestSupport.mapper(jdbc, SysConfigMapper.class);
        rateLimitCounterMapper = SchemaTestSupport.mapper(jdbc, RateLimitCounterMapper.class);
        ingestLogMapper = SchemaTestSupport.mapper(jdbc, IngestLogMapper.class);
        qualityCheckLogMapper = SchemaTestSupport.mapper(jdbc, QualityCheckLogMapper.class);
        batchExecutor = SchemaTestSupport.batchExecutor(jdbc);
    }

    @Test
    void localDateTime类型处理器写入读出值一致() {
        LocalDateTime start = LocalDateTime.of(2026, 11, 24, 17, 42, 13);
        LocalDateTime end = LocalDateTime.of(2026, 11, 24, 17, 45, 59);

        IngestLogEntity entity = new IngestLogEntity();
        entity.setRunId("RUN-TS");
        entity.setTableName("daily");
        entity.setJobType("INCR");
        entity.setStatus("SUCCESS");
        entity.setRowsFetched(100L);
        entity.setRowsWritten(100L);
        entity.setRetryCount(0);
        entity.setStartTime(start);
        entity.setEndTime(end);
        entity.setDurationMs(226_000L);
        entity.setCreatedAt(start);
        ingestLogMapper.insert(entity);

        IngestLogEntity loaded = ingestLogMapper.selectById(entity.getId());
        assertThat(loaded).isNotNull();
        assertThat(loaded.getStartTime()).isEqualTo(start);
        assertThat(loaded.getEndTime()).isEqualTo(end);
        assertThat(loaded.getCreatedAt()).isEqualTo(start);

        // 库内是文本（date_class=TEXT），保证与 created_at >= ? 文本比较口径一致
        Object raw = jdbc.queryForObject("SELECT typeof(start_time) FROM ingest_log WHERE id = ?",
                Object.class, entity.getId());
        assertThat(String.valueOf(raw)).isEqualTo("text");
    }

    @Test
    void sysConfig的onConflictUpsert重复写同key只有一行且值更新() {
        SysConfigEntity first = new SysConfigEntity();
        first.setConfigKey("ingest.daily.hk_hold");
        first.setConfigValue("true");
        first.setRemark("首次写入");
        first.setUpdatedAt(LocalDateTime.of(2026, 11, 24, 9, 0, 0));
        sysConfigMapper.upsert(first);

        SysConfigEntity second = new SysConfigEntity();
        second.setConfigKey("ingest.daily.hk_hold");
        second.setConfigValue("false");
        second.setRemark("运维台关闭");
        second.setUpdatedAt(LocalDateTime.of(2026, 11, 24, 18, 0, 0));
        sysConfigMapper.upsert(second);

        Long rows = jdbc.queryForObject("SELECT COUNT(*) FROM sys_config WHERE config_key = ?", Long.class,
                "ingest.daily.hk_hold");
        assertThat(rows).isEqualTo(1L);
        SysConfigEntity stored = sysConfigMapper.selectOne(Wrappers.<SysConfigEntity>lambdaQuery()
                .eq(SysConfigEntity::getConfigKey, "ingest.daily.hk_hold"));
        assertThat(stored.getConfigValue()).isEqualTo("false");
        assertThat(stored.getRemark()).isEqualTo("运维台关闭");
        assertThat(stored.getUpdatedAt()).isEqualTo(LocalDateTime.of(2026, 11, 24, 18, 0, 0));

        // null 值路径：remark 为空时 SQLite 需要 jdbcTypeForNull=NULL 才能正确绑定
        SysConfigEntity nullable = new SysConfigEntity();
        nullable.setConfigKey("ingest.daily.probe_null");
        nullable.setConfigValue("1");
        nullable.setRemark(null);
        nullable.setUpdatedAt(LocalDateTime.of(2026, 11, 24, 19, 0, 0));
        sysConfigMapper.upsert(nullable);
        SysConfigEntity nullRemark = sysConfigMapper.selectOne(Wrappers.<SysConfigEntity>lambdaQuery()
                .eq(SysConfigEntity::getConfigKey, "ingest.daily.probe_null"));
        assertThat(nullRemark.getRemark()).isNull();
        assertThat(nullRemark.getConfigValue()).isEqualTo("1");
    }

    @Test
    void 限流计数按api与分钟原子累加() {
        for (int i = 0; i < 5; i++) {
            rateLimitCounterMapper.increment("daily", "202611241800");
        }
        rateLimitCounterMapper.increment("adj_factor", "202611241800");

        Integer dailyCount = jdbc.queryForObject(
                "SELECT call_count FROM rate_limit_counter WHERE api_name = 'daily' AND minute_slot = ?",
                Integer.class, "202611241800");
        assertThat(dailyCount).isEqualTo(5);
        Long distinctRows = jdbc.queryForObject("SELECT COUNT(*) FROM rate_limit_counter", Long.class);
        assertThat(distinctRows).isEqualTo(2L);
    }

    @Test
    void mybatis批量执行器按批写入动态表() {
        TushareTableMapper mapper = SchemaTestSupport.mapper(jdbc, TushareTableMapper.class);
        List<Map<String, Object>> rows = new ArrayList<>();
        for (int i = 0; i < 1200; i++) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("ts_code", String.format("%06d.SZ", i));
            row.put("trade_date", "20261124");
            row.put("close", 10.5);
            row.put("vol", 1000);
            rows.add(row);
        }
        List<String> columns = List.of("ts_code", "trade_date", "close", "vol");

        int written = batchExecutor.execute(TushareTableMapper.class, rows,
                (m, row) -> m.upsert("daily", columns, List.of("ts_code", "trade_date"), row));

        assertThat(written).isEqualTo(1200);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM daily", Long.class)).isEqualTo(1200L);
        // 重跑同批数据：幂等（不翻倍）
        batchExecutor.execute(TushareTableMapper.class, rows,
                (m, row) -> m.upsert("daily", columns, List.of("ts_code", "trade_date"), row));
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM daily", Long.class)).isEqualTo(1200L);
    }

    @Test
    void qualityCheckLog实体写入与lambda查询() {
        QualityCheckLogEntity entity = new QualityCheckLogEntity();
        entity.setCheckTime(LocalDateTime.of(2026, 11, 24, 18, 5, 0));
        entity.setTriggerType("MANUAL");
        entity.setResult("PASS");
        entity.setItems("{\"tradeDate\":\"20261124\",\"items\":[]}");
        entity.setDurationMs(812L);
        qualityCheckLogMapper.insert(entity);

        QualityCheckLogEntity loaded = qualityCheckLogMapper.selectOne(
                Wrappers.<QualityCheckLogEntity>lambdaQuery().orderByDesc(QualityCheckLogEntity::getId)
                        .last("LIMIT 1"));
        assertThat(loaded.getResult()).isEqualTo("PASS");
        assertThat(loaded.getCheckTime()).isEqualTo(LocalDateTime.of(2026, 11, 24, 18, 5, 0));
        assertThat(loaded.getItems()).contains("20261124");
    }
}
