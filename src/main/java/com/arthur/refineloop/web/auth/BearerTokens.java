package com.arthur.refineloop.web.auth;

import com.arthur.refineloop.common.util.Strings;

/**
 * {@code Authorization: Bearer <token>} 头的解析（唯一实现）。
 *
 * <p>为什么单独抽出来：登录/登出接口与拦截器都要解析同一个头，各写一遍迟早出现「大小写敏感度」
 * 或「前后空格」不一致；方案名按 RFC 7235 大小写不敏感。
 */
public final class BearerTokens {

    private static final String PREFIX = "Bearer ";

    private BearerTokens() {
    }

    /**
     * 从请求头取出令牌；缺失、方案不匹配或为空时返回 {@code null}（由调用方决定 401 文案）。
     */
    public static String extract(String authorizationHeader) {
        if (Strings.isBlank(authorizationHeader)) {
            return null;
        }
        String value = authorizationHeader.trim();
        if (value.length() <= PREFIX.length()
                || !value.regionMatches(true, 0, PREFIX, 0, PREFIX.length())) {
            return null;
        }
        String token = value.substring(PREFIX.length()).trim();
        return token.isEmpty() ? null : token;
    }
}
