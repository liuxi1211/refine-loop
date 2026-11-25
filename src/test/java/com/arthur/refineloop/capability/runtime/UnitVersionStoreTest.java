package com.arthur.refineloop.capability.runtime;

import com.arthur.refineloop.capability.spi.FactBundle;
import com.arthur.refineloop.capability.spi.InterpretResult;
import com.arthur.refineloop.capability.spi.InterpretUnit;
import com.arthur.refineloop.capability.spi.UnitType;
import com.arthur.refineloop.capability.spi.annotation.CapabilityUnit;
import com.arthur.refineloop.capability.support.DemoComputeUnit;
import com.arthur.refineloop.data.mapper.UnitVersionMapper;
import com.arthur.refineloop.support.SchemaTestSupport;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("UnitVersionStore：unit_version 幂等登记")
class UnitVersionStoreTest {

    private JdbcTemplate jdbc;
    private UnitRegistry registry;
    private UnitVersionStore store;

    @BeforeEach
    void setUp() {
        jdbc = SchemaTestSupport.newJdbcTemplate();
        UnitVersionMapper mapper = SchemaTestSupport.mapper(jdbc, UnitVersionMapper.class);
        Clock clock = Clock.fixed(Instant.parse("2026-11-26T08:00:00Z"), ZoneId.of("Asia/Shanghai"));
        registry = new UnitRegistry();
        store = new UnitVersionStore(mapper, clock);
    }

    @Test
    @DisplayName("compute 单元登记为 ACTIVE，无 prompt_path，重复同步只一行")
    void syncComputeUnitIsIdempotent() {
        UnitRegistry.Entry entry = registry.register(new DemoComputeUnit());
        store.sync(entry);
        store.sync(entry);

        Integer count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM unit_version WHERE unit_id='demo_compute'", Integer.class);
        assertThat(count).isEqualTo(1);

        var saved = store.find("demo_compute", "v1.0");
        assertThat(saved).isNotNull();
        assertThat(saved.getStatus()).isEqualTo("ACTIVE");
        assertThat(saved.getPromptPath()).isNull();
        assertThat(saved.getActivatedAt()).isNotNull();
    }

    @Test
    @DisplayName("interpret 单元登记 prompts/{id}/{version}.md")
    void syncInterpretUnitWritesPromptPath() {
        UnitRegistry.Entry entry = registry.register(new DemoInterpretUnit());
        store.sync(entry);

        var saved = store.find("demo_interpret", "v1.0");
        assertThat(saved.getPromptPath()).isEqualTo("prompts/demo_interpret/v1.0.md");
    }

    @CapabilityUnit(id = "demo_interpret", version = "v1.0", type = UnitType.INTERPRET)
    static class DemoInterpretUnit implements InterpretUnit {
        @Override
        public InterpretResult interpret(FactBundle facts) {
            return new InterpretResult("ok", Map.of(), false, java.util.List.of());
        }
    }
}
