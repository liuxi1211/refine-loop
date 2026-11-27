package com.arthur.refineloop.capability.runtime.validation.validators;

import com.arthur.refineloop.capability.runtime.validation.FactMetrics;
import com.arthur.refineloop.capability.runtime.validation.JudgmentValidator;
import com.arthur.refineloop.capability.runtime.validation.ValidationContext;
import com.arthur.refineloop.capability.runtime.validation.ValidationError;
import com.arthur.refineloop.common.util.Strings;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * ⑤ 报告正文数字扫描：所有数字必须能被溯源标记解释。
 *
 * <p>要封的旁路：{@code facts_used} 只校验结构化字段，模型完全可以在正文里写
 * 「PB 约 0.58 倍、较同业低 15%」而判断块里一条都不引用——数字照样进了报告、进了人的判断。
 * 因此正文里<b>任何未包裹 {@code [[metric|value]]} 的数字都是校验失败</b>（能力详案 5.5）。
 *
 * <p>两类检查：
 * <ol>
 *   <li>标记自身合法：{@code metric} 必须出现在 {@code facts_used} 中，{@code value} 与该条数值一致；</li>
 *   <li>标记之外无裸数字：掩掉「非事实数字」（日期、版本/流水线标识、章节与列表序号、代码块）后，
 *       剩余数字一律报错并给出行号与上下文。</li>
 * </ol>
 *
 * <p>为什么需要白名单式掩码：把标题序号、日期、{@code v1.0} 也判成裸数字，会让重试永远无法收敛。
 * 代价是「报告里的真数字被误掩」，因此掩码规则保持克制，且要求章节标题一律用 markdown 标题
 * （{@code ##}），列表序号必须带分隔符（{@code 1.}）。
 */
public class ReportNumberScanner implements JudgmentValidator {

    /** 校验器名。 */
    public static final String NAME = "report_numbers";

    /** 单次最多报出的裸数字条数（超出只报汇总，避免回喂文本淹没真正的问题）。 */
    public static final int MAX_BARE_NUMBER_ERRORS = 20;

    private static final int CONTEXT_CHARS = 12;

    private static final Pattern MARKER = Pattern.compile("\\[\\[([^\\[\\]|]+)\\|([^\\[\\]|]+)]]");
    private static final Pattern FENCED_CODE = Pattern.compile("(?s)```.*?```");
    private static final Pattern INLINE_CODE = Pattern.compile("`[^`\\n]*`");
    private static final Pattern ISO_DATE = Pattern.compile("\\d{4}[-/]\\d{1,2}[-/]\\d{1,2}");
    private static final Pattern CN_DATE = Pattern.compile("\\d{4}年\\d{1,2}月(?:\\d{1,2}日)?|\\d{1,2}月\\d{1,2}日");
    private static final Pattern VERSION = Pattern.compile("\\bv\\d+(?:\\.\\d+)?\\b");
    private static final Pattern PIPELINE_ID = Pattern.compile("(?<![A-Za-z0-9])[PSMT]\\d{1,2}(?![0-9])");
    private static final Pattern HEADING_NUMBER =
            Pattern.compile("(?m)^[ \\t]*#{1,6}[ \\t]+\\d{1,2}(?:\\.\\d{1,2})*[.、)]?[ \\t]+");
    private static final Pattern LIST_NUMBER = Pattern.compile("(?m)^[ \\t]*\\d{1,2}[.、)][ \\t]+");
    private static final Pattern SECTION_REF = Pattern.compile("第\\d+[章节条步]");
    private static final Pattern FOOTNOTE = Pattern.compile("\\[\\d+]");
    private static final Pattern SECURITY_CODE = Pattern.compile(
            "\\b\\d{6}\\.(?:SH|SZ|BJ|SI|CSI|CIS|HI)\\b", Pattern.CASE_INSENSITIVE);
    private static final Pattern MARKET_TERM = Pattern.compile(
            "(?<![A-Za-z0-9])(?:\\d{4}(?:Q[1-4]|H[12])|Q[1-4]|H[12]|T\\+\\d+|MA\\d+"
                    + "|(?:近)?\\d+年|\\d+日均线|(?:沪深|上证|中证|深证)\\d+)(?![A-Za-z0-9])",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern NUMBER_TOKEN = Pattern.compile("\\d[\\d,]*(?:\\.\\d+)?");

    private static final List<Pattern> MASKS = List.of(
            FENCED_CODE, INLINE_CODE, ISO_DATE, CN_DATE, VERSION, PIPELINE_ID,
            SECURITY_CODE, MARKET_TERM, HEADING_NUMBER, LIST_NUMBER, SECTION_REF, FOOTNOTE);

    @Override
    public String name() {
        return NAME;
    }

    @Override
    public List<ValidationError> validate(ValidationContext context) {
        String report = context.reportMd();
        if (Strings.isBlank(report)) {
            return List.of();
        }
        Map<String, BigDecimal> usedValues = factsUsedValues(context.judgmentBlock());
        List<ValidationError> errors = new ArrayList<>(markerErrors(report, usedValues));
        errors.addAll(bareNumberErrors(report, errors.size()));
        return errors;
    }

    // ================================================================ 标记检查

    private List<ValidationError> markerErrors(String report, Map<String, BigDecimal> usedValues) {
        List<ValidationError> errors = new ArrayList<>();
        Matcher matcher = MARKER.matcher(report);
        while (matcher.find()) {
            String metric = matcher.group(1).trim();
            String declaredText = matcher.group(2).trim();
            String path = "report:" + lineOf(report, matcher.start()) + "([[" + metric + "|" + declaredText + "]])";
            if (!usedValues.containsKey(metric)) {
                errors.add(new ValidationError(NAME, path, "marker_metric_unknown",
                        "溯源标记引用了 facts_used 之外的指标 " + metric
                                + "（标记内的指标名必须与 facts_used 一致）"));
                continue;
            }
            BigDecimal declared = FactMetrics.toBigDecimal(declaredText);
            if (declared == null) {
                errors.add(new ValidationError(NAME, path, "marker_value_not_numeric",
                        "溯源标记的数值必须是数字（当前 " + declaredText + "）"));
                continue;
            }
            BigDecimal actual = usedValues.get(metric);
            if (actual != null && !FactsUsageValidator.withinTolerance(actual, declared)) {
                errors.add(new ValidationError(NAME, path, "marker_value_mismatch",
                        "溯源标记数值与 facts_used 不一致：facts_used 为 " + actual.toPlainString()
                                + "，正文写 " + declared.toPlainString()));
            }
        }
        return errors;
    }

    private Map<String, BigDecimal> factsUsedValues(Map<String, Object> judgmentBlock) {
        Map<String, BigDecimal> values = new LinkedHashMap<>();
        Object raw = judgmentBlock.get("facts_used");
        if (!(raw instanceof List<?> used)) {
            return values;
        }
        for (Object item : used) {
            if (item instanceof Map<?, ?> entry) {
                String metric = Strings.trimToNull(
                        entry.get("metric") == null ? null : String.valueOf(entry.get("metric")));
                if (metric != null) {
                    values.putIfAbsent(metric, FactMetrics.toBigDecimal(entry.get("value")));
                }
            }
        }
        return values;
    }

    // ================================================================ 裸数字扫描

    private List<ValidationError> bareNumberErrors(String report, int alreadyReported) {
        String masked = maskAll(report);
        Matcher matcher = NUMBER_TOKEN.matcher(masked);
        Set<String> seen = new LinkedHashSet<>();
        List<ValidationError> errors = new ArrayList<>();
        int total = 0;
        while (matcher.find()) {
            String token = matcher.group();
            int line = lineOf(masked, matcher.start());
            if (!seen.add(line + "|" + token)) {
                continue;
            }
            total++;
            if (errors.size() + alreadyReported >= MAX_BARE_NUMBER_ERRORS) {
                continue;
            }
            errors.add(new ValidationError(NAME, "report:" + line, "bare_number",
                    "第 " + line + " 行出现未包裹溯源标记的数字「" + token + "」（上下文："
                            + snippet(report, matcher.start(), token.length())
                            + "）；所有数字必须写成 [[指标|数值]] 且指标名取自 facts_used"));
        }
        if (total > errors.size()) {
            errors.add(new ValidationError(NAME, "report", "bare_number_truncated",
                    "另有 " + (total - errors.size()) + " 处未包裹溯源标记的数字未逐条列出"));
        }
        return errors;
    }

    /** 依次掩码「非事实数字」，每次替换保持长度与换行不变，保证行号可回算。 */
    private String maskAll(String report) {
        String masked = mask(report, MARKER);
        for (Pattern pattern : MASKS) {
            masked = mask(masked, pattern);
        }
        return masked;
    }

    private String mask(String text, Pattern pattern) {
        Matcher matcher = pattern.matcher(text);
        StringBuilder builder = new StringBuilder(text.length());
        int last = 0;
        while (matcher.find()) {
            builder.append(text, last, matcher.start());
            blankInto(builder, matcher.group());
            last = matcher.end();
        }
        builder.append(text, last, text.length());
        return builder.toString();
    }

    private void blankInto(StringBuilder builder, String chunk) {
        for (int i = 0; i < chunk.length(); i++) {
            builder.append(chunk.charAt(i) == '\n' ? '\n' : ' ');
        }
    }

    private int lineOf(String text, int index) {
        int line = 1;
        int limit = Math.min(index, text.length());
        for (int i = 0; i < limit; i++) {
            if (text.charAt(i) == '\n') {
                line++;
            }
        }
        return line;
    }

    private String snippet(String report, int start, int tokenLength) {
        int from = Math.max(0, start - CONTEXT_CHARS);
        int to = Math.min(report.length(), start + tokenLength + CONTEXT_CHARS);
        return report.substring(from, to).replace('\n', ' ').trim();
    }
}
