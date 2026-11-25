package com.arthur.refineloop.it;

import com.arthur.refineloop.support.AuthTestSupport;
import com.arthur.refineloop.user.service.UserService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.web.servlet.MockMvc;

import static org.hamcrest.Matchers.hasItem;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 公共基础设施的端到端冒烟：字典接口、缓存运维接口、链路号响应头、未知路径 404。
 *
 * <p>与 {@code ApplicationStartupTest} 使用同一套 Spring 配置（含 DB URL），
 * 复用其上下文缓存，避免重复启动拖慢测试。
 *
 * <p>鉴权：公共基础设施端点同样要求登录（只有 {@code /api/health} 与登录端点公开），
 * 测试统一通过 {@link AuthTestSupport} 拿令牌。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK, properties = {
        "spring.datasource.url=jdbc:sqlite:./target/test-db/startup_it.db?journal_mode=WAL&busy_timeout=5000"
                + "&date_class=TEXT&foreign_keys=on&synchronous=NORMAL",
        "tushare.token="
})
@AutoConfigureMockMvc
class CommonInfraIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private UserService userService;

    private String token;

    @BeforeEach
    void authenticate() throws Exception {
        AuthTestSupport.ensureAdmin(userService);
        token = AuthTestSupport.login(mockMvc);
    }

    @Test
    void 字典接口一次下发全部内置枚举() throws Exception {
        mockMvc.perform(get("/api/common/dicts").with(AuthTestSupport.bearer(token)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data.yesNo").isArray())
                .andExpect(jsonPath("$.data.yesNo[0].code").value("1"))
                .andExpect(jsonPath("$.data.yesNo[0].label").value("是"))
                .andExpect(jsonPath("$.data.enableStatus").isArray())
                .andExpect(jsonPath("$.data.sortOrder").isArray())
                .andExpect(jsonPath("$.data.userRole").isArray());
    }

    @Test
    void 单个字典与未知字典分别返回200和404() throws Exception {
        mockMvc.perform(get("/api/common/dicts/yesNo").with(AuthTestSupport.bearer(token)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(2));

        mockMvc.perform(get("/api/common/dicts/notExists").with(AuthTestSupport.bearer(token)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(404));
    }

    @Test
    void 缓存运维接口可看到具名缓存并清空指定缓存() throws Exception {
        mockMvc.perform(get("/api/common/cache").with(AuthTestSupport.bearer(token)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data.named[*].name", hasItem("tradeCal")))
                .andExpect(jsonPath("$.data.named[0].name").exists());

        mockMvc.perform(delete("/api/common/cache/tradeCal").with(AuthTestSupport.bearer(token)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0));

        mockMvc.perform(delete("/api/common/cache/notExists").with(AuthTestSupport.bearer(token)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(404));
    }

    @Test
    void 每个响应都带链路号响应头() throws Exception {
        mockMvc.perform(get("/api/common/dicts").with(AuthTestSupport.bearer(token)))
                .andExpect(header().exists("X-Trace-Id"));
        // 上游传入的合法 traceId 应当被透传
        mockMvc.perform(get("/api/common/dicts").with(AuthTestSupport.bearer(token))
                        .header("X-Trace-Id", "upstream-123"))
                .andExpect(header().string("X-Trace-Id", "upstream-123"));
    }

    @Test
    void 未知路径返回404业务码而不是500() throws Exception {
        mockMvc.perform(get("/api/definitely-not-exists").with(AuthTestSupport.bearer(token)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(404));
    }
}
