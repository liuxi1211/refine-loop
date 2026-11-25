package com.arthur.refineloop.support;

import com.arthur.refineloop.user.model.UserCreateSpec;
import com.arthur.refineloop.user.model.UserRole;
import com.arthur.refineloop.user.service.UserService;
import com.jayway.jsonpath.JsonPath;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 集成测试的登录夹具：所有 {@code /api/**}（除公开端点）都要求登录，测试先造一个管理员再拿令牌。
 *
 * <p>为什么不用「关闭鉴权」的开关：那会让鉴权在测试里完全不执行，回归时拦不住 401/403 退化。
 * 本类走真实登录接口，令牌与生产同源；每个用例一个会话，互不污染。
 */
public final class AuthTestSupport {

    /** 集成测试管理员账号（与首次启动引导的 admin 区分，避免依赖库状态）。 */
    public static final String USERNAME = "it-admin";
    public static final String PASSWORD = "admin123";

    private AuthTestSupport() {
    }

    /** 幂等创建测试管理员（已存在则跳过）。 */
    public static void ensureAdmin(UserService userService) {
        if (userService.findByUsername(USERNAME).isEmpty()) {
            userService.create(new UserCreateSpec(USERNAME, PASSWORD, "集成测试管理员",
                    UserRole.ADMIN.code(), "AuthTestSupport 自动创建"));
        }
    }

    /** 走真实登录接口拿令牌。 */
    public static String login(MockMvc mockMvc) throws Exception {
        MvcResult result = mockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"" + USERNAME + "\",\"password\":\"" + PASSWORD + "\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andReturn();
        return JsonPath.read(result.getResponse().getContentAsString(java.nio.charset.StandardCharsets.UTF_8),
                "$.data.token");
    }

    /** 给请求加 {@code Authorization: Bearer <token>}。 */
    public static RequestPostProcessor bearer(String token) {
        return request -> {
            request.addHeader("Authorization", "Bearer " + token);
            return request;
        };
    }
}
