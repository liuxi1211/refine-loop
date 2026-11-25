package com.arthur.refineloop.web;

import com.arthur.refineloop.common.constant.CommonConstants;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.mvc.method.RequestMappingInfo;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * API 前缀的机器校验（规范 04 §1）：外部 URL 一律 {@code /api/...}，且前缀只挂一次。
 *
 * <p><b>为什么要有这个测试</b>：前缀已从 Controller 搬到 {@code WebMvcConfig.addPathPrefix}，
 * 好处是 11 个 Controller 不再各自拼字符串，代价是「前缀到底有没有生效」不再能从源码直接看出来。
 * 这里断言的是 Spring 实际注册的映射表（而不是扫源码），因此能同时抓住两类回归：
 * <ol>
 *   <li><b>漏挂</b>：Controller 写 {@code /users} 但没走 addPathPrefix → 端点裸奔在 {@code /users}；</li>
 *   <li><b>重复挂</b>：有人又手写 {@code @RequestMapping("/api/users")} → 端点变成 {@code /api/api/users}。</li>
 * </ol>
 *
 * <p>只校验 {@code com.arthur.refineloop.web} 下的控制器：springdoc 等第三方端点不归本规范管，
 * 这也是 {@code WebMvcConfig} 用「包名」而非「@RestController 注解」划定前缀范围的原因。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK, properties = {
        "spring.datasource.url=jdbc:sqlite:./target/test-db/api-prefix_it.db?journal_mode=WAL&busy_timeout=5000"
                + "&date_class=TEXT&foreign_keys=on&synchronous=NORMAL",
        "tushare.token="
})
class ApiPathPrefixTest {

    /** 本项目控制器所在包：只有这里的映射受 /api 前缀约束。 */
    private static final String CONTROLLER_PACKAGE = "com.arthur.refineloop.web";

    private static final String PREFIX = CommonConstants.API_PREFIX + "/";

    @Autowired
    private RequestMappingHandlerMapping handlerMapping;

    @Test
    @DisplayName("所有业务端点都以 /api/ 开头，且没有 /api/api 双重前缀")
    void 所有业务端点都带且只带一个api前缀() {
        List<String> violations = new ArrayList<>();
        int checked = 0;

        for (Map.Entry<RequestMappingInfo, HandlerMethod> entry : handlerMapping.getHandlerMethods().entrySet()) {
            HandlerMethod handler = entry.getValue();
            if (!handler.getBeanType().getPackageName().startsWith(CONTROLLER_PACKAGE)) {
                continue;
            }
            for (String pattern : entry.getKey().getPatternValues()) {
                checked++;
                if (!hasExactlyOnePrefix(pattern)) {
                    violations.add(pattern + " ← " + handler.getShortLogMessage());
                }
            }
        }

        assertThat(checked).as("必须至少扫到一个业务端点，否则这条断言是空转").isPositive();
        assertThat(violations)
                .as("端点路径必须由 WebMvcConfig.addPathPrefix 统一挂 /api（Controller 内不要手写前缀）")
                .isEmpty();
    }

    /** {@code /api/users} ✓；{@code /users} ✗（漏挂）；{@code /api/api/users} ✗（重复挂）。 */
    private static boolean hasExactlyOnePrefix(String pattern) {
        if (!pattern.startsWith(PREFIX)) {
            return false;
        }
        String remainder = pattern.substring(PREFIX.length());
        return !remainder.equals("api") && !remainder.startsWith("api/");
    }
}
