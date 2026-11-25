package com.arthur.refineloop.common.dict;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.List;

/**
 * 字典装配：把「扫描基础包 + 收集动态 Provider」收敛到一个 Bean 定义里，
 * 让 {@link DictService} 保持纯逻辑、可脱离 Spring 单测。
 *
 * <p>扫描范围限定本应用根包 {@code com.arthur.refineloop}，不会误扫第三方 jar；
 * {@code @PostConstruct} 在 Bean 初始化时构建快照，重复编码会让应用启动失败（故意的）。
 */
@Configuration
public class DictConfig {

    @Bean
    public DictService dictService(List<DictProvider> providers) {
        return new DictService("com.arthur.refineloop", providers);
    }
}
