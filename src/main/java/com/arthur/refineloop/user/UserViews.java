package com.arthur.refineloop.user;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

/**
 * 账号域出参视图集合（{@code web} 层只做序列化，不再定义出参）。
 *
 * <p>全部以 {@code Result<T>} 包装返回；系统时间用 {@link LocalDateTime}（Jackson 输出 ISO 字符串），
 * 业务日期统一 {@code yyyyMMdd} 字符串。{@code null} 字段由 Jackson 配置直接省略。
 */
public final class UserViews {

    private UserViews() {
    }

    /** 用户列表行（不含权限明细，列表页不展开）。 */
    public record UserSummary(
            long id,
            String username,
            String displayName,
            String role,
            String roleLabel,
            boolean active,
            LocalDateTime lastLoginAt,
            LocalDateTime createdAt) {
    }

    /** 用户详情（含角色展开后的权限码）。 */
    public record UserDetail(
            long id,
            String username,
            String displayName,
            String role,
            String roleLabel,
            boolean active,
            String remark,
            LocalDateTime lastLoginAt,
            LocalDateTime createdAt,
            LocalDateTime updatedAt,
            List<String> permissions) {
    }

    /** 当前登录用户（{@code GET /api/auth/me}）。 */
    public record CurrentUser(
            long id,
            String username,
            String displayName,
            String role,
            String roleLabel,
            List<String> permissions) {
    }

    /** 登录结果：明文令牌只在这里出现一次。 */
    public record LoginResult(String token, LocalDateTime expiresAt, CurrentUser user) {
    }

    /** 用户交易偏好（键值对；值为字符串，复杂结构由调用方约定 JSON 文本）。 */
    public record PreferenceView(long userId, Map<String, String> preferences) {
    }

    /** 自选股条目。 */
    public record WatchlistItem(
            long id,
            String tsCode,
            String stockName,
            String groupName,
            String note,
            int sortNo,
            LocalDateTime createdAt) {
    }

    /** 自选股列表（当前用户维度）。 */
    public record WatchlistOverview(List<WatchlistItem> items, int total) {
    }
}
