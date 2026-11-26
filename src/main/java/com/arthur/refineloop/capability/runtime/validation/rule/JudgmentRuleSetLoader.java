package com.arthur.refineloop.capability.runtime.validation.rule;

import com.arthur.refineloop.capability.runtime.CapabilityErrorCode;
import com.arthur.refineloop.common.ApiException;
import com.arthur.refineloop.common.util.JsonUtils;
import com.arthur.refineloop.common.util.Strings;
import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSONObject;
import org.springframework.core.io.ClassPathResource;
import org.springframework.util.StreamUtils;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 规则集加载器：由判断 Schema id 推导规则文件并解析，按 schemaId 缓存。
 *
 * <p>映射约定：{@code interpret_valuation-v1.json → schema/interpret_valuation-v1.rules.json}
 * ——规则与 Schema 同版本同目录，换版本即换文件，杜绝「升了 Schema 忘了升规则」的一半生效状态。
 *
 * <p>缓存说明：解析结果是不可变对象，进程内缓存安全；规则文件随发布变更，不需要热更（热更会
 * 破坏判断的版本可溯性，架构 §4.2.9 已明确拒绝）。
 */
public class JudgmentRuleSetLoader {

    private final Map<String, JudgmentRuleSet> cache = new ConcurrentHashMap<>();

    /**
     * 加载某个 Schema 对应的规则集。
     *
     * @param schemaId 判断 Schema 资源名（如 {@code interpret_valuation-v1.json}）
     * @return 规则集；文件不存在时视为「该单元无特有规则」并返回空集
     * @throws ApiException 4005 文件内容非法（JSON 错、缺 id、id 重复）
     */
    public JudgmentRuleSet load(String schemaId) {
        String key = Strings.trimToNull(schemaId);
        if (key == null) {
            throw invalid("schemaId 不能为空（无法定位规则集）");
        }
        return cache.computeIfAbsent(key, this::read);
    }

    /** 由 Schema id 推导规则文件路径（公开以便装配期自检与文档对齐）。 */
    public static String rulesResource(String schemaId) {
        String base = schemaId.endsWith(".json")
                ? schemaId.substring(0, schemaId.length() - ".json".length())
                : schemaId;
        return "schema/" + base + ".rules.json";
    }

    private JudgmentRuleSet read(String schemaId) {
        String path = rulesResource(schemaId);
        ClassPathResource resource = new ClassPathResource(path);
        if (!resource.exists()) {
            return JudgmentRuleSet.empty();
        }
        JSONObject root = parse(path, readText(path, resource));
        return new JudgmentRuleSet(root.getString("description"), parseRules(path, root.getJSONArray("rules")));
    }

    private List<JudgmentRuleSpec> parseRules(String path, JSONArray array) {
        if (array == null || array.isEmpty()) {
            return List.of();
        }
        List<JudgmentRuleSpec> specs = new ArrayList<>(array.size());
        Set<String> seen = new HashSet<>();
        for (int i = 0; i < array.size(); i++) {
            JSONObject item;
            try {
                item = array.getJSONObject(i);
            } catch (RuntimeException e) {
                throw invalid("规则集第 " + (i + 1) + " 项不是对象：" + path);
            }
            String id = item == null ? null : Strings.trimToNull(item.getString("id"));
            if (id == null) {
                throw invalid("规则集第 " + (i + 1) + " 项缺少 id：" + path);
            }
            if (!seen.add(id)) {
                throw invalid("规则集 id 重复：" + id + "（" + path + "）");
            }
            Map<String, Object> params = item.getJSONObject("params");
            specs.add(new JudgmentRuleSpec(id, params));
        }
        return List.copyOf(specs);
    }

    private JSONObject parse(String path, String content) {
        try {
            return JsonUtils.parseObject(content);
        } catch (RuntimeException e) {
            throw invalid("规则集不是合法 JSON 对象：" + path + "（" + e.getMessage() + "）");
        }
    }

    private String readText(String path, ClassPathResource resource) {
        try (InputStream in = resource.getInputStream()) {
            String content = StreamUtils.copyToString(in, StandardCharsets.UTF_8);
            if (content.isBlank()) {
                throw invalid("规则集内容为空：" + path);
            }
            return content;
        } catch (IOException e) {
            throw invalid("规则集读取失败：" + path + "（" + e.getMessage() + "）");
        }
    }

    private static ApiException invalid(String message) {
        return ApiException.of(CapabilityErrorCode.VALIDATION_CONFIG_INVALID, message);
    }
}
