package com.arthur.refineloop.capability.runtime.llm;

import org.springframework.core.io.ClassPathResource;
import org.springframework.util.StreamUtils;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 提示词版本化加载：{@code prompts/{promptId}/{version}.md}。
 *
 * <p>版本冻结：同一 (promptId, version) 永远对应同一内容，加载后缓存；不提供“按最新”兜底，
 * 防止单元版本与提示词版本脱钩。
 */
public class PromptTemplateLoader {

    private static final Pattern PROMPT_ID = Pattern.compile("^[a-z][a-z0-9_]{2,39}$");
    private static final Pattern VERSION = Pattern.compile("^v\\d+(\\.\\d+)?$");
    private static final Pattern VARIABLE = Pattern.compile("\\{\\{(\\w+)}}");

    private final Map<String, String> cache = new ConcurrentHashMap<>();

    /**
     * 加载提示词原文。
     */
    public String load(String promptId, String version) {
        validate(promptId, version);
        String key = promptId + "/" + version;
        return cache.computeIfAbsent(key, ignored -> readClasspath(promptId, version));
    }

    /**
     * 替换 {@code {{variable}}} 占位符；变量缺失直接失败，避免 LLM 看到未渲染模板。
     */
    public String render(String template, Map<String, Object> variables) {
        if (template == null || template.isEmpty()) {
            throw LlmException.promptNotFound("提示词内容为空");
        }
        Map<String, Object> safeVariables = variables == null ? Map.of() : variables;
        Matcher matcher = VARIABLE.matcher(template);
        StringBuilder rendered = new StringBuilder(template.length());
        while (matcher.find()) {
            String name = matcher.group(1);
            if (!safeVariables.containsKey(name) || safeVariables.get(name) == null) {
                throw LlmException.promptVariableMissing("提示词变量缺失：" + name);
            }
            matcher.appendReplacement(rendered,
                    Matcher.quoteReplacement(String.valueOf(safeVariables.get(name))));
        }
        matcher.appendTail(rendered);
        return rendered.toString();
    }

    private String readClasspath(String promptId, String version) {
        String path = "prompts/" + promptId + "/" + version + ".md";
        ClassPathResource resource = new ClassPathResource(path);
        if (!resource.exists()) {
            throw LlmException.promptNotFound("提示词文件不存在：" + path);
        }
        try (InputStream in = resource.getInputStream()) {
            String content = StreamUtils.copyToString(in, StandardCharsets.UTF_8);
            if (content.isBlank()) {
                throw LlmException.promptNotFound("提示词文件为空：" + path);
            }
            return content;
        } catch (IOException e) {
            throw LlmException.promptNotFound("提示词读取失败：" + path);
        }
    }

    private void validate(String promptId, String version) {
        if (promptId == null || !PROMPT_ID.matcher(promptId).matches()) {
            throw LlmException.promptNotFound("promptId 非法：" + promptId);
        }
        if (version == null || !VERSION.matcher(version).matches()) {
            throw LlmException.promptNotFound("prompt 版本非法：" + version);
        }
    }
}
