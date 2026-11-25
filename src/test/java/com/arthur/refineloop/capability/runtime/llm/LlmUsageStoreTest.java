package com.arthur.refineloop.capability.runtime.llm;

import com.arthur.refineloop.data.mapper.LlmUsageMapper;
import com.arthur.refineloop.support.SchemaTestSupport;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("LlmUsageStore：token 与费用记账")
class LlmUsageStoreTest {

    private JdbcTemplate jdbc;
    private LlmUsageStore store;

    @BeforeEach
    void setUp() {
        jdbc = SchemaTestSupport.newJdbcTemplate();
        LlmUsageMapper mapper = SchemaTestSupport.mapper(jdbc, LlmUsageMapper.class);
        Clock clock = Clock.fixed(Instant.parse("2026-11-26T08:00:00Z"), ZoneId.of("Asia/Shanghai"));
        store = new LlmUsageStore(mapper, clock);
    }

    @Test
    @DisplayName("写入一行含 profile/token/费用")
    void recordWritesRow() {
        long id = store.record("run-1", "unit-1", "fast", "deepseek-chat",
                10, 20, 30, new BigDecimal("0.000050"));

        assertThat(id).isPositive();
        Map<String, Object> row = jdbc.queryForMap("SELECT * FROM llm_usage WHERE id = ?", id);
        assertThat(row).containsEntry("run_id", "run-1");
        assertThat(row).containsEntry("unit_id", "unit-1");
        assertThat(row).containsEntry("model_profile", "fast");
        assertThat(row).containsEntry("model", "deepseek-chat");
        assertThat(((Number) row.get("prompt_tokens")).intValue()).isEqualTo(10);
        assertThat(((Number) row.get("completion_tokens")).intValue()).isEqualTo(20);
        assertThat(((Number) row.get("total_tokens")).intValue()).isEqualTo(30);
        assertThat(new BigDecimal(row.get("cost").toString())).isEqualByComparingTo("0.00005");
        LocalDateTime createdAt = LocalDateTime.parse(
                row.get("created_at").toString().replace(' ', 'T'));
        assertThat(createdAt).isNotNull();
    }

    @Test
    @DisplayName("费用按百万 token 单价估算，6 位小数")
    void estimateCostUsesConfiguredPrices() {
        BigDecimal cost = LlmUsageStore.estimateCost(1_000_000, 2_000_000,
                new BigDecimal("1.0"), new BigDecimal("2.0"));
        assertThat(cost).isEqualByComparingTo("5.000000");
    }
}
