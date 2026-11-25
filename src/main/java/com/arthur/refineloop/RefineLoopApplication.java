package com.arthur.refineloop;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

/**
 * 个人股票分析与验证闭环系统 — 单服务器单体应用入口。
 *
 * <p>包结构约束见《技术架构与模块设计》§3：单 Maven 模块、按领域分包，
 * 模块间依赖方向为 web/pipeline → capability → tushare/common，禁止反向依赖。
 */
@SpringBootApplication
@ConfigurationPropertiesScan
public class RefineLoopApplication {

    public static void main(String[] args) {
        SpringApplication.run(RefineLoopApplication.class, args);
    }
}
