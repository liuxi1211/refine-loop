package com.arthur.refineloop.support;

import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSONObject;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * T4/T5 单测共用的 Tushare mock 响应构造器。
 *
 * <p>不依赖 SchemaTestSupport（后者由 Agent A 提供），仅构造 Tushare 列式响应 JSON。
 */
public final class TushareMockResponses {

    private TushareMockResponses() {
    }

    /** 构造 {"code":0,"msg":"","data":{"fields":[...],"items":[[...],...]}}。 */
    public static String ok(String fields, List<List<Object>> items) {
        JSONObject root = new JSONObject();
        root.put("code", 0);
        root.put("msg", "");
        JSONObject data = new JSONObject();
        JSONArray fieldArray = new JSONArray();
        for (String field : fields.split(",")) {
            fieldArray.add(field.trim());
        }
        data.put("fields", fieldArray);
        JSONArray itemArray = new JSONArray();
        for (List<Object> row : items) {
            JSONArray rowArray = new JSONArray();
            rowArray.addAll(row);
            itemArray.add(rowArray);
        }
        data.put("items", itemArray);
        root.put("data", data);
        return root.toJSONString();
    }

    /** 构造错误响应：{"code":code,"msg":msg,"data":null}。 */
    public static String error(int code, String msg) {
        JSONObject root = new JSONObject();
        root.put("code", code);
        root.put("msg", msg);
        root.put("data", null);
        return root.toJSONString();
    }

    /** 构造若干行（按 fields 顺序给值），用于简洁断言。 */
    public static List<List<Object>> rows(Object[]... rows) {
        List<List<Object>> result = new ArrayList<>();
        for (Object[] row : rows) {
            List<Object> values = new ArrayList<>(row.length);
            for (Object value : row) {
                values.add(value);
            }
            result.add(values);
        }
        return result;
    }

    /** 构造 n 行相同模板（分页测试用）。 */
    public static List<List<Object>> repeatedRows(int count, java.util.function.IntFunction<List<Object>> factory) {
        List<List<Object>> result = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            result.add(factory.apply(i));
        }
        return result;
    }

    /** 便于构造简单 Map 参数（测试断言用）。 */
    public static Map<String, Object> map(Object... kv) {
        Map<String, Object> result = new LinkedHashMap<>();
        for (int i = 0; i + 1 < kv.length; i += 2) {
            result.put(String.valueOf(kv[i]), kv[i + 1]);
        }
        return result;
    }
}
