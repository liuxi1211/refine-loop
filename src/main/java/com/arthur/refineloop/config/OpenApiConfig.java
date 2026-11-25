package com.arthur.refineloop.config;

import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Contact;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.info.License;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * OpenAPI 文档元信息：{@code /swagger-ui.html} 的标题、版本、说明。
 *
 * <p>个人项目也值得维护：前后端联调时「接口契约以 springdoc 为准」，比口头约定可靠。
 * 新增控制器不需要在这里登记，springdoc 会自动扫描。
 */
@Configuration
public class OpenApiConfig {

    @Bean
    public OpenAPI refineLoopOpenAPI() {
        return new OpenAPI()
                .info(new Info()
                        .title("RefineLoop API")
                        .version("0.1.0")
                        .description("""
                                个人股票分析与验证闭环系统。
                                统一响应：{code, msg, data}；code=0 成功，4xx/5xx 为沟通层错误，1xxx 起为业务码。
                                链路排查：响应头 X-Trace-Id 与日志中的 traceId 一一对应。
                                """)
                        .contact(new Contact().name("RefineLoop"))
                        .license(new License().name("Private")));
    }
}
