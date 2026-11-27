package com.arthur.refineloop.capability;

import com.arthur.refineloop.capability.runtime.FactStore;
import com.arthur.refineloop.capability.runtime.UnitRegistry;
import com.arthur.refineloop.capability.runtime.UnitVersionStore;
import com.arthur.refineloop.capability.spi.BatchFactResult;
import com.arthur.refineloop.capability.spi.ComputeUnit;
import com.arthur.refineloop.capability.spi.FactContext;
import com.arthur.refineloop.capability.spi.FactResult;
import com.arthur.refineloop.capability.spi.StockPool;
import com.arthur.refineloop.capability.support.DemoComputeUnit;
import com.arthur.refineloop.data.mapper.UnitFactMapper;
import com.arthur.refineloop.data.mapper.UnitVersionMapper;
import com.arthur.refineloop.support.SchemaTestSupport;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("T6 验收：注册 → 执行 → 落 unit_fact")
class CapabilityEndToEndTest {

    @Test
    void demoComputeUnitRunsAndPersistsFacts() {
        JdbcTemplate jdbc = SchemaTestSupport.newJdbcTemplate();
        Clock clock = Clock.fixed(Instant.parse("2026-11-26T08:00:00Z"), ZoneId.of("Asia/Shanghai"));
        FactStore factStore = new FactStore(
                SchemaTestSupport.mapper(jdbc, UnitFactMapper.class),
                new TransactionTemplate(new DataSourceTransactionManager(jdbc.getDataSource())), clock);
        UnitVersionStore versionStore = new UnitVersionStore(
                SchemaTestSupport.mapper(jdbc, UnitVersionMapper.class), clock);
        UnitRegistry registry = new UnitRegistry();

        UnitRegistry.Entry entry = registry.register(new DemoComputeUnit());
        versionStore.sync(entry);

        ComputeUnit unit = (ComputeUnit) entry.instance();
        BatchFactResult result = unit.computeBatch(
                new FactContext("20261126", "run-t6"),
                StockPool.of("demo", List.of("000001.SZ", "600000.SH")));
        assertThat(result.size()).isEqualTo(2);

        assertThat(factStore.saveAll("run-t6", "20261126", result.facts())).isEqualTo(2);
        List<FactResult> loaded = factStore.findByUnit("run-t6", "demo_compute", "v1.0", "20261126");
        assertThat(loaded).hasSize(2);
        assertThat(loaded.get(0).payload()).containsEntry("tradeDate", "20261126");
        assertThat(versionStore.find("demo_compute", "v1.0").getStatus()).isEqualTo("ACTIVE");
    }
}
