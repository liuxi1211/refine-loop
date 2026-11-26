package com.arthur.refineloop.capability.runtime.llm;

import com.arthur.refineloop.common.util.JsonUtils;
import com.arthur.refineloop.common.util.Strings;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * LLM 输出解析器：把「Markdown 正文 + 一个 json 围栏」拆成 {@link UnitRawOutput}。
 *
 * <p>为什么取<b>最后一个</b> json 围栏：提示词要求判断块放在末尾，而正文里的示例 JSON
 * 通常出现在前面；取最后一个既能容忍正文示例，也能在模型「先给块后解释」时少一次误判
 * （那种情况会被正文数字扫描兜住）。
 *
 * <p>不吞异常：解析失败不抛错，而是写进 {@code judgmentParseError} 交给校验链短路——
 * 模型格式错误属可预期失败，重试一次往往就能修正。
 */
public final class InterpretOutputParser {

    private static final Pattern JSON_FENCE =
            Pattern.compile("(?s)```[ \\t]*(?i:json)?[ \\t]*\\R(.*?)```");
    private static final int MAX_ERROR_LENGTH = 160;

    private InterpretOutputParser() {
    }

    /**
     * 解析模型输出。
     *
     * @param rawText 模型返回原文（可为 null）
     * @return 拆解结果；失败时 {@code judgmentParseError} 非空
     */
    public static UnitRawOutput parse(String rawText) {
        String text = Strings.nullToEmpty(rawText);
        if (text.isBlank()) {
            return UnitRawOutput.unparsed(text, "", "LLM 返回为空，无法解析判断块");
        }
        Fence fence = lastJsonFence(text);
        if (fence != null) {
            return parseFence(text, fence);
        }
        return parseBareJson(text);
    }

    /** 文本中所有 json 围栏的起始位置（排查用）。 */
    static List<Integer> fenceStarts(String text) {
        Matcher matcher = JSON_FENCE.matcher(Strings.nullToEmpty(text));
        List<Integer> starts = new ArrayList<>();
        while (matcher.find()) {
            starts.add(matcher.start());
        }
        return List.copyOf(starts);
    }

    private static Fence lastJsonFence(String text) {
        Matcher matcher = JSON_FENCE.matcher(text);
        Fence last = null;
        while (matcher.find()) {
            last = new Fence(matcher.start(), matcher.end(), matcher.group(1));
        }
        return last;
    }

    private static UnitRawOutput parseFence(String text, Fence fence) {
        String report = (text.substring(0, fence.start()) + "\n" + text.substring(fence.end())).trim();
        String json = fence.content() == null ? "" : fence.content().trim();
        if (json.isEmpty()) {
            return UnitRawOutput.unparsed(text, report, "json 代码块为空：必须包含判断块对象");
        }
        try {
            Map<String, Object> block = JsonUtils.parseMap(json);
            if (block.isEmpty()) {
                return UnitRawOutput.unparsed(text, report, "判断块为空对象：必须包含完整字段");
            }
            return new UnitRawOutput(report, block, null, text);
        } catch (RuntimeException e) {
            return UnitRawOutput.unparsed(text, report,
                    "判断块不是合法 JSON 对象：" + Strings.truncate(e.getMessage(), MAX_ERROR_LENGTH));
        }
    }

    /** 没有围栏时，容忍模型直接吐一个 JSON 对象（此时报告正文为空）。 */
    private static UnitRawOutput parseBareJson(String text) {
        String trimmed = text.trim();
        if (trimmed.startsWith("{") && trimmed.endsWith("}")) {
            try {
                Map<String, Object> block = JsonUtils.parseMap(trimmed);
                if (!block.isEmpty()) {
                    return new UnitRawOutput("", block, null, text);
                }
            } catch (RuntimeException e) {
                return UnitRawOutput.unparsed(text, "",
                        "未找到 ```json 判断块，且返回内容不是合法 JSON 对象");
            }
        }
        return new UnitRawOutput(text.trim(), Map.of(),
                "未找到 ```json 判断块：输出必须包含报告正文 + 末尾一个 json 代码块", text);
    }

    /** 一次围栏匹配结果（只取需要的三要素，避免把 Matcher 传出去）。 */
    private record Fence(int start, int end, String content) {
    }
}
