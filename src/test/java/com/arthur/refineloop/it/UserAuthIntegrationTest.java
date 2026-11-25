package com.arthur.refineloop.it;

import com.arthur.refineloop.support.AuthTestSupport;
import com.arthur.refineloop.support.SchemaTestSupport;
import com.arthur.refineloop.user.model.UserCreateSpec;
import com.arthur.refineloop.user.model.UserRole;
import com.arthur.refineloop.user.service.UserService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.hasItem;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 账号与权限的端到端验收（真实 Spring 上下文 + 真实 SQLite + 真实拦截器）。
 *
 * <p>覆盖：公开端点、未认证 401、登录/登出、当前用户、角色越权 403、按用户隔离的自选股。
 * 与 {@code OpsApiIntegrationTest} 使用独立库文件，避免相互污染。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK, properties = {
        "spring.datasource.url=jdbc:sqlite:./target/test-db/user_auth_it.db?journal_mode=WAL&busy_timeout=5000"
                + "&date_class=TEXT&foreign_keys=on&synchronous=NORMAL",
        "tushare.token="
})
@AutoConfigureMockMvc
class UserAuthIntegrationTest {

    private static final String PLAIN_USER = "plain-user";
    private static final String PLAIN_PASSWORD = "Plain@123";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private UserService userService;

    private String adminToken;

    @BeforeEach
    void setUp() throws Exception {
        SchemaTestSupport.reset(jdbc);
        AuthTestSupport.ensureAdmin(userService);
        adminToken = AuthTestSupport.login(mockMvc);
    }

    @Test
    @DisplayName("健康检查是公开端点：无需令牌")
    void healthIsPublic() throws Exception {
        mockMvc.perform(get("/api/health"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data.status").value("UP"));
    }

    @Test
    @DisplayName("未认证访问受保护端点 → 401（HTTP 仍 200）")
    void protectedEndpointRequiresToken() throws Exception {
        mockMvc.perform(get("/api/ops/tables"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(401));
        mockMvc.perform(get("/api/ops/tables").header("Authorization", "Bearer not-a-real-token"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(401));
        mockMvc.perform(get("/api/ops/tables").header("Authorization", "Basic abc"))
                .andExpect(jsonPath("$.code").value(401));
    }

    @Test
    @DisplayName("登录成功：/api/auth/me 返回用户名、角色与权限码")
    void loginAndCurrentUser() throws Exception {
        mockMvc.perform(get("/api/auth/me").with(AuthTestSupport.bearer(adminToken)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data.username").value(AuthTestSupport.USERNAME))
                .andExpect(jsonPath("$.data.role").value(UserRole.ADMIN.code()))
                .andExpect(jsonPath("$.data.permissions", hasItem("user:write")))
                .andExpect(jsonPath("$.data.permissions", hasItem("data:ingest")));
    }

    @Test
    @DisplayName("错误密码 / 不存在用户 → 401")
    void loginRejectsBadCredentials() throws Exception {
        mockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"" + AuthTestSupport.USERNAME + "\",\"password\":\"wrong\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(401))
                .andExpect(jsonPath("$.msg", containsString("用户名或密码错误")));

        mockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"nobody\",\"password\":\"whatever\"}"))
                .andExpect(jsonPath("$.code").value(401));
    }

    @Test
    @DisplayName("登录参数缺失 → 400")
    void loginValidatesRequest() throws Exception {
        mockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(400));
    }

    @Test
    @DisplayName("普通用户：可读行情数据，调用用户管理接口 → 403")
    void plainUserCannotManageUsers() throws Exception {
        userService.create(new UserCreateSpec(PLAIN_USER, PLAIN_PASSWORD, "普通用户",
                UserRole.USER.code(), null));
        String plainToken = login(PLAIN_USER, PLAIN_PASSWORD);

        mockMvc.perform(get("/api/ops/tables").with(AuthTestSupport.bearer(plainToken)))
                .andExpect(jsonPath("$.code").value(0));

        mockMvc.perform(get("/api/users").with(AuthTestSupport.bearer(plainToken)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(403));

        mockMvc.perform(post("/api/users").with(AuthTestSupport.bearer(plainToken))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"hacker\",\"password\":\"Hacker@123\"}"))
                .andExpect(jsonPath("$.code").value(403));

        mockMvc.perform(post("/api/ops/tables/daily/run").with(AuthTestSupport.bearer(plainToken)))
                .andExpect(jsonPath("$.code").value(403));
    }

    @Test
    @DisplayName("登出后令牌立即失效")
    void logoutInvalidatesToken() throws Exception {
        String token = AuthTestSupport.login(mockMvc);

        mockMvc.perform(post("/api/auth/logout").with(AuthTestSupport.bearer(token)))
                .andExpect(jsonPath("$.code").value(0));

        mockMvc.perform(get("/api/auth/me").with(AuthTestSupport.bearer(token)))
                .andExpect(jsonPath("$.code").value(401));
    }

    @Test
    @DisplayName("用户管理：管理员可建用户，用户列表可见，用户名重复 → 409")
    void adminManagesUsers() throws Exception {
        mockMvc.perform(post("/api/users").with(AuthTestSupport.bearer(adminToken))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"newbie\",\"password\":\"Newbie@123\",\"displayName\":\"新人\","
                                + "\"role\":\"USER\"}"))
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data.username").value("newbie"))
                .andExpect(jsonPath("$.data.role").value("USER"));

        mockMvc.perform(get("/api/users").with(AuthTestSupport.bearer(adminToken)))
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data.total").value(2));

        mockMvc.perform(post("/api/users").with(AuthTestSupport.bearer(adminToken))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"newbie\",\"password\":\"Newbie@123\"}"))
                .andExpect(jsonPath("$.code").value(409));
    }

    @Test
    @DisplayName("自选股按登录用户隔离：A 新增，B 看不到")
    void watchlistIsIsolatedPerUser() throws Exception {
        mockMvc.perform(post("/api/users/me/watchlist").with(AuthTestSupport.bearer(adminToken))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"tsCode\":\"000001.SZ\"}"))
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data.tsCode").value("000001.SZ"));

        userService.create(new UserCreateSpec(PLAIN_USER, PLAIN_PASSWORD, "普通用户",
                UserRole.USER.code(), null));
        String plainToken = login(PLAIN_USER, PLAIN_PASSWORD);

        mockMvc.perform(get("/api/users/me/watchlist").with(AuthTestSupport.bearer(plainToken)))
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data.total").value(0));
        mockMvc.perform(get("/api/users/me/watchlist").with(AuthTestSupport.bearer(adminToken)))
                .andExpect(jsonPath("$.data.total").value(1));
    }

    @Test
    @DisplayName("交易偏好：PUT 整组替换后 GET 读回")
    void preferencesRoundTrip() throws Exception {
        mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                        .put("/api/users/me/preferences").with(AuthTestSupport.bearer(adminToken))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"preferences\":{\"trading.style\":\"SWING\",\"risk.maxPositionPct\":\"30\"}}"))
                .andExpect(jsonPath("$.code").value(0));

        mockMvc.perform(get("/api/users/me/preferences").with(AuthTestSupport.bearer(adminToken)))
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data.preferences['trading.style']").value("SWING"))
                .andExpect(jsonPath("$.data.preferences['risk.maxPositionPct']").value("30"));
    }

    private String login(String username, String password) throws Exception {
        MvcResult result = mockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"" + username + "\",\"password\":\"" + password + "\"}"))
                .andExpect(jsonPath("$.code").value(0))
                .andReturn();
        return com.jayway.jsonpath.JsonPath.read(
                result.getResponse().getContentAsString(java.nio.charset.StandardCharsets.UTF_8),
                "$.data.token");
    }
}
