package com.arthur.refineloop.capability.runtime;

import com.arthur.refineloop.capability.spi.FactDataStatus;
import com.arthur.refineloop.capability.spi.FactResult;
import com.arthur.refineloop.common.ApiException;
import com.arthur.refineloop.data.mapper.UnitFactMapper;
import com.arthur.refineloop.support.SchemaTestSupport;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DisplayName("FactStore：unit_fact 按 run 追加、不可覆盖")
class FactStoreTest {

    private JdbcTemplate jdbc;
    private FactStore store;

    @BeforeEach
    void setUp() {
        jdbc = SchemaTestSupport.newJdbcTemplate();
        Clock clock = Clock.fixed(Instant.parse("2026-11-26T08:00:00Z"), ZoneId.of("Asia/Shanghai"));
        store = new FactStore(SchemaTestSupport.mapper(jdbc, UnitFactMapper.class),
                new TransactionTemplate(new DataSourceTransactionManager(jdbc.getDataSource())), clock);
    }

    @Test
    @DisplayName("写入后可按 run 读回 payload、状态、notes 与 schemaVersion")
    void saveAndLoad() {
        store.saveAll("run-a", "20261126", List.of(
                fact("000001.SZ", FactDataStatus.COMPLETE, Map.of("pct", 1.5), List.of("正常")),
                fact("600000.SH", FactDataStatus.DEGRADED, Map.of("pct", 0.8), List.of("窗口不足"))));

        List<FactResult> facts = store.findByUnit("run-a", "calc_test", "v1.0", "20261126");
        assertThat(facts).hasSize(2);
        assertThat(facts).extracting(FactResult::tsCode).containsExactly("000001.SZ", "600000.SH");
        assertThat(facts.get(0).schemaVersion()).isEqualTo("schema-v1");
        assertThat(facts.get(0).notes()).containsExactly("正常");
        assertThat(new BigDecimal(facts.get(0).payload().get("pct").toString())).isEqualByComparingTo("1.5");
        assertThat(facts.get(1).dataStatus()).isEqualTo(FactDataStatus.DEGRADED);
        assertThat(facts.get(1).notes()).containsExactly("窗口不足");
        assertThat(store.findByTradeDateAndTsCode("run-a", "20261126", "600000.SH")).hasSize(1);
    }

    @Test
    @DisplayName("同 run 相同事实重复提交为 no-op；内容变化拒绝覆盖")
    void sameRunIsAppendOnly() {
        FactResult original = fact("000001.SZ", FactDataStatus.COMPLETE, Map.of("pct", 1.5), List.of());
        assertThat(store.save("run-a", "20261126", original)).isEqualTo(1);
        assertThat(store.save("run-a", "20261126", original)).isZero();

        FactResult changed = fact("000001.SZ", FactDataStatus.DEGRADED, Map.of("pct", 2.5), List.of());
        assertThatThrownBy(() -> store.save("run-a", "20261126", changed))
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("拒绝覆盖");
        assertThat(store.findByTradeDateAndTsCode("run-a", "20261126", "000001.SZ"))
                .extracting(FactResult::dataStatus).containsExactly(FactDataStatus.COMPLETE);
    }

    @Test
    @DisplayName("不同 run 重算产生新行，旧 run 证据保持不变")
    void differentRunsDoNotOverwriteEvidence() {
        store.save("run-a", "20261126", fact("000001.SZ", FactDataStatus.COMPLETE, Map.of("pct", 1.5), List.of()));
        store.save("run-b", "20261126", fact("000001.SZ", FactDataStatus.DEGRADED, Map.of("pct", 2.5), List.of()));

        assertThat(store.findByTradeDateAndTsCode("run-a", "20261126", "000001.SZ"))
                .extracting(FactResult::dataStatus).containsExactly(FactDataStatus.COMPLETE);
        assertThat(store.findByTradeDateAndTsCode("run-b", "20261126", "000001.SZ"))
                .extracting(FactResult::dataStatus).containsExactly(FactDataStatus.DEGRADED);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM unit_fact", Long.class)).isEqualTo(2L);
    }

    @Test
    @DisplayName("缺 runId 直接拒绝，防止退回覆盖模式")
    void requiresRunId() {
        assertThatThrownBy(() -> store.saveAll(" ", "20261126",
                List.of(fact("000001.SZ", FactDataStatus.COMPLETE, Map.of("pct", 1.5), List.of()))))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("runId");
    }

    private FactResult fact(String tsCode, FactDataStatus status, Map<String, Object> payload, List<String> notes) {
        return new FactResult(tsCode, "calc_test", "v1.0", "schema-v1", status, payload, notes);
    }
}
