package com.arthur.refineloop.common.util;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSONObject;
import com.alibaba.fastjson2.JSONWriter;
import com.alibaba.fastjson2.TypeReference;

import java.lang.reflect.Type;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * JSON 工具（fastjson2）：业务序列化的<b>唯一</b>入口。
 *
 * <p>项目约定（架构 §1）：业务 JSON 一律 fastjson2，不混用 Jackson；
 * Spring MVC 的 HTTP 序列化仍由 Jackson 负责，两者互不干扰。
 *
 * <p>为什么不直接调 {@code JSON.*}：异常语义统一（解析失败 → {@link IllegalArgumentException}），
 * 并给「解析失败但允许降级」的场景提供 {@code tryParse*}（返回 Optional，不抛异常）。
 * 永远不要用返回 null 表示解析失败——那会和「合法 JSON null」混为一谈。
 */
public final class JsonUtils {

    private JsonUtils() {
    }

    /* ---------------- 序列化 ---------------- */

    public static String toJson(Object value) {
        return JSON.toJSONString(value);
    }

    /** 格式化输出（落盘报告/排查用，别用于 HTTP 响应）。 */
    public static String toPrettyJson(Object value) {
        return JSON.toJSONString(value, JSONWriter.Feature.PrettyFormat);
    }

    /* ---------------- 反序列化 ---------------- */

    public static <T> T parse(String json, Class<T> type) {
        if (Strings.isBlank(json)) {
            throw new IllegalArgumentException("JSON 解析失败：内容为空");
        }
        try {
            return JSON.parseObject(json, type);
        } catch (RuntimeException e) {
            throw new IllegalArgumentException("JSON 解析失败：" + Strings.truncate(json, 200), e);
        }
    }

    public static <T> T parse(String json, TypeReference<T> type) {
        if (Strings.isBlank(json)) {
            throw new IllegalArgumentException("JSON 解析失败：内容为空");
        }
        try {
            return JSON.parseObject(json, type);
        } catch (RuntimeException e) {
            throw new IllegalArgumentException("JSON 解析失败：" + Strings.truncate(json, 200), e);
        }
    }

    public static <T> List<T> parseList(String json, Class<T> elementType) {
        if (Strings.isBlank(json)) {
            return List.of();
        }
        try {
            List<T> parsed = JSON.parseArray(json, elementType);
            return parsed == null ? List.of() : parsed;
        } catch (RuntimeException e) {
            throw new IllegalArgumentException("JSON 数组解析失败：" + Strings.truncate(json, 200), e);
        }
    }

    @SuppressWarnings("unchecked")
    public static Map<String, Object> parseMap(String json) {
        if (Strings.isBlank(json)) {
            return Map.of();
        }
        try {
            JSONObject object = JSON.parseObject(json);
            return object == null ? Map.of() : object;
        } catch (RuntimeException e) {
            throw new IllegalArgumentException("JSON 对象解析失败：" + Strings.truncate(json, 200), e);
        }
    }

    public static JSONObject parseObject(String json) {
        return parse(json, JSONObject.class);
    }

    public static JSONArray parseArray(String json) {
        if (Strings.isBlank(json)) {
            return new JSONArray();
        }
        return JSON.parseArray(json);
    }

    /* ---------------- 容错解析 ---------------- */

    /** 解析失败返回 empty（用于「外部返回格式不可信」的降级路径）。 */
    public static <T> Optional<T> tryParse(String json, Class<T> type) {
        try {
            return Optional.ofNullable(parse(json, type));
        } catch (RuntimeException e) {
            return Optional.empty();
        }
    }

    public static <T> Optional<List<T>> tryParseList(String json, Class<T> elementType) {
        try {
            return Optional.of(parseList(json, elementType));
        } catch (RuntimeException e) {
            return Optional.empty();
        }
    }

    /** 是否为合法 JSON 文本（空串视为不合法）。 */
    public static boolean isValid(String json) {
        return Strings.isNotBlank(json) && JSON.isValid(json);
    }

    /** 泛型反序列化便捷入口（{@code Type} 版本，供框架层使用）。 */
    public static <T> T parse(String json, Type type) {
        if (Strings.isBlank(json)) {
            throw new IllegalArgumentException("JSON 解析失败：内容为空");
        }
        try {
            return JSON.parseObject(json, type);
        } catch (RuntimeException e) {
            throw new IllegalArgumentException("JSON 解析失败：" + Strings.truncate(json, 200), e);
        }
    }
}
