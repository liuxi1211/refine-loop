package com.arthur.refineloop.capability.runtime;

import com.arthur.refineloop.capability.spi.FactDataStatus;
import com.arthur.refineloop.capability.spi.FactResult;
import com.arthur.refineloop.data.mapper.UnitFactMapper;
import com.arthur.refineloop.support.SchemaTestSupport;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("FactStore：unit_fact 幂等读写")
class FactStoreTest {

    private JdbcTemplate jdbc;
    private FactStore store;

    @BeforeEach
    void setUp() {
        jdbc = SchemaTestSupport.newJdbcTemplate();
        UnitFactMapper mapper = SchemaTestSupport.mapper(jdbc, UnitFactMapper.class);
        Clock clock = Clock.fixed(Instant.parse("2026-11-26T08:00:00Z"), ZoneId.of("Asia/Shanghai"));
        store = new FactStore(mapper, clock);
    }

    @Test
    @DisplayName("批量写入后可读出 payload 与状态")
    void saveAndLoad() {
        store.saveAll("20261126", List.of(
                fact("000001.SZ", FactDataStatus.COMPLETE, Map.of("pct", 1.5)),
                fact("600000.SH", FactDataStatus.DEGRADED, Map.of("pct", 0.8))));

        List<FactResult> facts = store.findByUnit("calc_test", "v1.0", "20261126");
        assertThat(facts).hasSize(2);
        assertThat(facts).extracting(FactResult::tsCode)
                .containsExactly("000001.SZ", "600000.SH");
        assertThat(facts.get(0).dataStatus()).isEqualTo(FactDataStatus.COMPLETE);
        assertThat(new java.math.BigDecimal(facts.get(0).payload().get("pct").toString()))
                .isEqualByComparingTo("1.5");
        assertThat(facts.get(1).dataStatus()).isEqualTo(FactDataStatus.DEGRADED);

        assertThat(store.findByTradeDateAndTsCode("20261126", "600000.SH")).hasSize(1);
    }

    @Test
    @DisplayName("同键重跑只更新不重复")
    void upsertIsIdempotent() {
        store.save("20261126", fact("000001.SZ", FactDataStatus.COMPLETE, Map.of("pct", 1.5)));
        store.save("20261126", fact("000001.SZ", FactDataStatus.DEGRADED, Map.of("pct", 2.5)));

        Integer count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM unit_fact WHERE unit_id='calc_test'", Integer.class);
        assertThat(count).isEqualTo(1);

        FactResult loaded = store.findByUnit("calc_test", "v1.0", "20261126").get(0);
        assertThat(new java.math.BigDecimal(loaded.payload().get("pct").toString()))
                .isEqualByComparingTo("2.5");
        assertThat(loaded.dataStatus()).isEqualTo(FactDataStatus.DEGRADED);
    }

    private FactResult fact(String tsCode, FactDataStatus status, Map<String, Object> payload) {
        return new FactResult(tsCode, "calc_test", "v1.0", "v1.0", status, payload, List.of());
    }
}
