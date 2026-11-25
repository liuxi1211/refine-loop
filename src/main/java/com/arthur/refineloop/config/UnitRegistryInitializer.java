package com.arthur.refineloop.config;

import com.arthur.refineloop.capability.runtime.UnitRegistry;
import com.arthur.refineloop.capability.runtime.UnitVersionStore;
import com.arthur.refineloop.capability.spi.annotation.CapabilityUnit;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.ApplicationContext;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import java.util.Map;

/**
 * 启动期扫描 {@code @CapabilityUnit} Bean：注册到 {@link UnitRegistry}，并同步 {@code unit_version}。
 *
 * <p>重复 unitId、缺注解、type/scope 不匹配等直接让启动失败（fail-fast），
 * 避免带着半套能力单元进入运行。
 */
@Slf4j
@Component
@Order(10)
public class UnitRegistryInitializer implements ApplicationRunner {

    private final ApplicationContext applicationContext;
    private final UnitRegistry unitRegistry;
    private final UnitVersionStore unitVersionStore;

    public UnitRegistryInitializer(ApplicationContext applicationContext,
                                   UnitRegistry unitRegistry,
                                   UnitVersionStore unitVersionStore) {
        this.applicationContext = applicationContext;
        this.unitRegistry = unitRegistry;
        this.unitVersionStore = unitVersionStore;
    }

    @Override
    public void run(ApplicationArguments args) {
        Map<String, Object> beans = applicationContext.getBeansWithAnnotation(CapabilityUnit.class);
        if (beans.isEmpty()) {
            log.info("[capability] 未发现能力单元 Bean（T9 起按需接入）");
            return;
        }
        beans.forEach((name, bean) -> {
            UnitRegistry.Entry entry = unitRegistry.register(bean);
            unitVersionStore.sync(entry);
        });
        log.info("[capability] 能力单元注册完成：{} 个", unitRegistry.size());
    }
}
