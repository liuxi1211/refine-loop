package com.arthur.refineloop;

import com.arthur.refineloop.support.AuthTestSupport;
import com.arthur.refineloop.user.service.UserService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.web.servlet.MockMvc;

import static org.hamcrest.Matchers.greaterThanOrEqualTo;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 启动冒烟（T2~T5 联调验收项）：SQLite 库文件不存在时应用必须能启动，
 * 由 {@code SqliteSchemaInitializer} 自动建库建表（61 张表），健康检查与运维 API 立即可用。
 *
 * <p>数据库引擎迁移为 SQLite 后，不再有「无数据库导致 503」的场景：
 * 启动即可用，因此本测同时充当「自动建库 + 首启可用」的验收。
 *
 * <p>鉴权：{@code /api/health} 是公开端点；运维端点需要登录，测试通过 {@link AuthTestSupport}
 * 走真实登录接口拿令牌（不关闭鉴权，避免回归时拦不住 401 退化）。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK, properties = {
        "spring.datasource.url=jdbc:sqlite:./target/test-db/startup_it.db?journal_mode=WAL&busy_timeout=5000"
                + "&date_class=TEXT&foreign_keys=on&synchronous=NORMAL",
        "tushare.token="
})
@AutoConfigureMockMvc
class ApplicationStartupTest {

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
    void 应用上下文可启动且健康检查正常() throws Exception {
        mockMvc.perform(get("/api/health"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data.status").value("UP"))
                .andExpect(jsonPath("$.data.time").isString());
    }

    @Test
    void 首次启动自动建库建表后表总览立即可用() throws Exception {
        mockMvc.perform(get("/api/ops/tables").with(AuthTestSupport.bearer(token)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data.summary.total").value(41))
                .andExpect(jsonPath("$.data.tables.length()").value(41));
    }

    @Test
    void 首启空库时运维统计与质量门端点返回空态而不是报错() throws Exception {
        mockMvc.perform(get("/api/ops/stats/today").with(AuthTestSupport.bearer(token)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data.jobs.total").value(0))
                .andExpect(jsonPath("$.data.t1").doesNotExist())
                .andExpect(jsonPath("$.data.tushareCallsToday").value(0));

        mockMvc.perform(get("/api/ops/quality/latest").with(AuthTestSupport.bearer(token)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0));

        mockMvc.perform(get("/api/ops/ingest/orchestrations/current").with(AuthTestSupport.bearer(token)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data.layers.length()", greaterThanOrEqualTo(3)));
    }
}
