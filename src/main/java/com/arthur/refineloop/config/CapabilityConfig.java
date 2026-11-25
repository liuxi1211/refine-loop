package com.arthur.refineloop.config;

import com.arthur.refineloop.capability.runtime.FactStore;
import com.arthur.refineloop.capability.runtime.UnitRegistry;
import com.arthur.refineloop.capability.runtime.UnitVersionStore;
import com.arthur.refineloop.data.mapper.UnitFactMapper;
import com.arthur.refineloop.data.mapper.UnitVersionMapper;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Clock;

/**
 * 能力单元框架装配：注册表、事实存取、版本登记。
 *
 * <p>运行时类保持为纯类（便于单测直接 new），由本配置显式装配。
 */
@Configuration
public class CapabilityConfig {

    @Bean
    public UnitRegistry unitRegistry() {
        return new UnitRegistry();
    }

    @Bean
    public FactStore factStore(UnitFactMapper unitFactMapper, Clock clock) {
        return new FactStore(unitFactMapper, clock);
    }

    @Bean
    public UnitVersionStore unitVersionStore(UnitVersionMapper unitVersionMapper, Clock clock) {
        return new UnitVersionStore(unitVersionMapper, clock);
    }
}
