package com.arthur.refineloop.web.user;

import com.arthur.refineloop.common.validation.EnumCode;
import com.arthur.refineloop.user.model.UserCreateSpec;
import com.arthur.refineloop.user.model.UserRole;
import com.arthur.refineloop.user.model.UserUpdateSpec;
import com.arthur.refineloop.user.model.WatchlistSpec;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

import java.util.Map;

/**
 * 账号域请求体集合（{@code web} 适配层入参；响应视图见 {@code user.UserViews}）。
 *
 * <p>校验分工：格式/范围在 record 组件上用注解（{@code @NotBlank}/{@code @Size}/{@code @EnumCode}），
 * 业务规则（用户名唯一、至少一个管理员、密码策略）在 service 判定。
 */
public final class UserRequests {

    private UserRequests() {
    }

    // ================================================================ 认证

    /** POST /api/auth/login 请求体。 */
    public record LoginRequest(
            @NotBlank(message = "用户名不能为空") String username,
            @NotBlank(message = "密码不能为空") String password) {
    }

    /** POST /api/auth/password 请求体（自助改密）。 */
    public record ChangePasswordRequest(
            @NotBlank(message = "原密码不能为空") String oldPassword,
            @NotBlank(message = "新密码不能为空") String newPassword) {
    }

    // ================================================================ 用户管理

    /** POST /api/users 请求体。 */
    public record CreateUserRequest(
            @NotBlank(message = "用户名不能为空") @Size(max = 64, message = "用户名不能超过 64 个字符") String username,
            @NotBlank(message = "密码不能为空") String password,
            @Size(max = 64, message = "显示名不能超过 64 个字符") String displayName,
            @EnumCode(UserRole.class) String role,
            @Size(max = 255, message = "备注不能超过 255 个字符") String remark) {

        public UserCreateSpec toSpec() {
            return new UserCreateSpec(username, password, displayName, role, remark);
        }
    }

    /** PUT /api/users/{id} 请求体（null 字段不修改）。 */
    public record UpdateUserRequest(
            @Size(max = 64, message = "显示名不能超过 64 个字符") String displayName,
            @EnumCode(UserRole.class) String role,
            Boolean active,
            @Size(max = 255, message = "备注不能超过 255 个字符") String remark) {

        public UserUpdateSpec toSpec() {
            return new UserUpdateSpec(displayName, role, active, remark);
        }
    }

    /** POST /api/users/{id}/password 请求体（管理员重置密码）。 */
    public record ResetPasswordRequest(
            @NotBlank(message = "新密码不能为空") String password) {
    }

    // ================================================================ 我的偏好 / 自选股

    /** PUT /api/users/me/preferences 请求体：整组替换当前用户的偏好。 */
    public record PreferencesRequest(Map<String, String> preferences) {
    }

    /** POST /api/users/me/watchlist 请求体（新增）。 */
    public record WatchlistRequest(
            @NotBlank(message = "标的代码不能为空") String tsCode,
            @Size(max = 128, message = "股票名称不能超过 128 个字符") String stockName,
            @Size(max = 32, message = "分组不能超过 32 个字符") String groupName,
            @Size(max = 255, message = "备注不能超过 255 个字符") String note,
            Integer sortNo) {

        public WatchlistSpec toSpec() {
            return new WatchlistSpec(tsCode, stockName, groupName, note, sortNo);
        }
    }

    /** PUT /api/users/me/watchlist/{id} 请求体：标的不可改，只改分组/备注/名称快照/排序号。 */
    public record WatchlistUpdateRequest(
            @Size(max = 128, message = "股票名称不能超过 128 个字符") String stockName,
            @Size(max = 32, message = "分组不能超过 32 个字符") String groupName,
            @Size(max = 255, message = "备注不能超过 255 个字符") String note,
            Integer sortNo) {

        public WatchlistSpec toSpec() {
            return new WatchlistSpec(null, stockName, groupName, note, sortNo);
        }
    }
}
