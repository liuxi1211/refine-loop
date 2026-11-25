package com.arthur.refineloop.common.util;

import com.arthur.refineloop.common.constant.CommonConstants;

/**
 * 脱敏工具：token、手机号、邮箱、长 ID 进日志/报错文案前必须过一遍。
 *
 * <p>本项目的硬性要求（架构文档「token 不入日志」）：Tushare token、LLM api-key 一律不得明文落日志。
 * 需要打印配置/请求头时用 {@link #maskSecret(String)}，不要图省事直接拼原值。
 */
public final class MaskUtils {

    private MaskUtils() {
    }

    /** 完全隐藏（密钥类，连长度都不该暴露给前端）。 */
    public static String maskSecret(String secret) {
        return Strings.isBlank(secret) ? "" : CommonConstants.MASK;
    }

    /** token：保留前 4 位便于区分环境，其余隐藏；过短直接全隐藏。 */
    public static String maskToken(String token) {
        if (Strings.isBlank(token)) {
            return "";
        }
        String trimmed = token.trim();
        if (trimmed.length() <= 8) {
            return CommonConstants.MASK;
        }
        return trimmed.substring(0, 4) + "****" + trimmed.substring(trimmed.length() - 2);
    }

    /** 手机号：138****1234。 */
    public static String maskPhone(String phone) {
        if (Strings.isBlank(phone) || phone.length() < 7) {
            return CommonConstants.MASK;
        }
        String trimmed = phone.trim();
        return trimmed.substring(0, 3) + "****" + trimmed.substring(trimmed.length() - 4);
    }

    /** 邮箱：ab***@example.com。 */
    public static String maskEmail(String email) {
        if (Strings.isBlank(email)) {
            return "";
        }
        int at = email.indexOf('@');
        if (at <= 0) {
            return maskToken(email);
        }
        String local = email.substring(0, at);
        String domain = email.substring(at);
        if (local.length() <= 2) {
            return "*" + domain;
        }
        return local.charAt(0) + "***" + local.charAt(local.length() - 1) + domain;
    }

    /** 身份证/长编号：保留前 4 后 4。 */
    public static String maskIdentifier(String id) {
        if (Strings.isBlank(id)) {
            return "";
        }
        String trimmed = id.trim();
        if (trimmed.length() <= 8) {
            return CommonConstants.MASK;
        }
        return trimmed.substring(0, 4) + "********" + trimmed.substring(trimmed.length() - 4);
    }

    /** 通用脱敏：保留首尾各 2 位。 */
    public static String mask(String value) {
        if (Strings.isBlank(value)) {
            return "";
        }
        String trimmed = value.trim();
        if (trimmed.length() <= 4) {
            return CommonConstants.MASK;
        }
        return trimmed.charAt(0) + "" + trimmed.charAt(1) + "****"
                + trimmed.charAt(trimmed.length() - 2) + trimmed.charAt(trimmed.length() - 1);
    }
}
