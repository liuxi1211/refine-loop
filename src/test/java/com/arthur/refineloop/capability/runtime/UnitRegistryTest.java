package com.arthur.refineloop.capability.runtime;

import com.arthur.refineloop.capability.spi.ComputeUnit;
import com.arthur.refineloop.capability.spi.FactBundle;
import com.arthur.refineloop.capability.spi.InterpretResult;
import com.arthur.refineloop.capability.spi.InterpretUnit;
import com.arthur.refineloop.capability.spi.UnitScope;
import com.arthur.refineloop.capability.spi.UnitType;
import com.arthur.refineloop.capability.spi.annotation.CapabilityUnit;
import com.arthur.refineloop.capability.support.DemoComputeUnit;
import com.arthur.refineloop.common.ApiException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.catchThrowableOfType;

@DisplayName("UnitRegistry：启动期注册与校验")
class UnitRegistryTest {

    @Test
    @DisplayName("正常注册后可查询")
    void registerAndFind() {
        UnitRegistry registry = new UnitRegistry();
        UnitRegistry.Entry entry = registry.register(new DemoComputeUnit());

        assertThat(entry.unitId()).isEqualTo("demo_compute");
        assertThat(entry.version()).isEqualTo("v1.0");
        assertThat(entry.type()).isEqualTo(UnitType.COMPUTE);
        assertThat(entry.scope()).isEqualTo(UnitScope.BATCH);
        assertThat(registry.size()).isEqualTo(1);
        assertThat(registry.find("demo_compute")).isSameAs(entry);
        assertThat(registry.require("demo_compute").instance()).isInstanceOf(ComputeUnit.class);
    }

    @Test
    @DisplayName("重复 unitId 抛 4001")
    void duplicateUnitRejected() {
        UnitRegistry registry = new UnitRegistry();
        registry.register(new DemoComputeUnit());

        ApiException error = catchThrowableOfType(
                () -> registry.register(new DemoComputeUnit()), ApiException.class);
        assertThat(error).isNotNull();
        assertThat(error.getCode()).isEqualTo(4001);
    }

    @Test
    @DisplayName("缺注解抛 4002")
    void missingAnnotationRejected() {
        UnitRegistry registry = new UnitRegistry();

        ApiException error = catchThrowableOfType(
                () -> registry.register(new PlainComputeUnit()), ApiException.class);
        assertThat(error).isNotNull();
        assertThat(error.getCode()).isEqualTo(4002);
    }

    @Test
    @DisplayName("INTERPRET 单元必须是 PER_STOCK")
    void interpretScopeMustBePerStock() {
        UnitRegistry registry = new UnitRegistry();

        ApiException error = catchThrowableOfType(
                () -> registry.register(new BadInterpretUnit()), ApiException.class);
        assertThat(error).isNotNull();
        assertThat(error.getCode()).isEqualTo(4002);
    }

    @Test
    @DisplayName("未注册单元抛 4004")
    void notFoundRejected() {
        UnitRegistry registry = new UnitRegistry();
        ApiException error = catchThrowableOfType(() -> registry.require("unknown"), ApiException.class);
        assertThat(error).isNotNull();
        assertThat(error.getCode()).isEqualTo(4004);
    }

    static class PlainComputeUnit implements com.arthur.refineloop.capability.spi.CapabilityUnit {
    }

    @CapabilityUnit(id = "bad_interpret", version = "v1", type = UnitType.INTERPRET, scope = UnitScope.BATCH)
    static class BadInterpretUnit implements InterpretUnit {
        @Override
        public InterpretResult interpret(FactBundle facts) {
            return null;
        }
    }
}
