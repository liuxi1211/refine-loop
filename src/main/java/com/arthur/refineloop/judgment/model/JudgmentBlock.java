package com.arthur.refineloop.judgment.model;

import com.arthur.refineloop.common.ApiException;
import com.arthur.refineloop.common.util.Enums;
import com.arthur.refineloop.common.util.Strings;
import com.arthur.refineloop.judgment.JudgmentErrorCode;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 判断块的结构化视图：把 interpret 单元产出的 {@code Map} 一次性解析成强类型，顺带完成「块级」校验。
 *
 * <p><b>为什么需要它</b>：存档器要消费的是能力详案 3.1/3.2 的<b>契约字段</b>，而契约以 JSON 承载
 * （各单元维度不同，无法共用 Java 类型）。若不先解析，组装代码里会散落几十处
 * {@code (Map) block.get("x")} + 类型转换 + 判空——既超长又容易在某个分支漏判。
 *
 * <p><b>边界</b>：本类只校验「主判断必需字段的形状」（方向枚举、有效期范围、四类验证规则、档位枚举）。
 * 判断块<b>内容</b>的合法性由 L3 五重校验链在产出侧保证（{@code capability.runtime.validation}），
 * 这里不重复实现——否则同一规则两处维护，迟早漂移。
 *
 * <p><b>不变量</b>：
 * <ul>
 *   <li>解析失败一律 6001（判断载荷不合法），不允许「缺字段就塞默认值」悄悄存档；</li>
 *   <li>降级块允许缺 {@code thesis}/{@code key_levels}/{@code confidence}，按机械规则补齐（能力详案 7.3）；</li>
 *   <li>所有数值统一转 {@link BigDecimal}（避免 double/int 在 payload 里出现两种表示）。</li>
 * </ul>
 */
public record JudgmentBlock(
        Identity identity,
        Direction direction,
        String confidence,
        KeyLevels keyLevels,
        Thesis thesis,
        List<VerificationRule> verificationRules,
        List<String> logicTags,
        List<String> dataGaps) {

    /** 降级判断的固定文案：不含数字与主观措辞（判断块是机器资产，不能用叙事掩盖降级）。 */
    public static final String DEGRADED_THESIS = "降级：未产出多空论证（原因见 data_gaps）";

    /** 四类验证规则（能力详案 3.3 契约 1，流水线3 巡检的唯一触发依据）。 */
    public static final List<String> RULE_TYPES =
            List.of("price_touch", "price_break", "direction_fail", "logic_invalid");

    /** thesis.current_side 的取值（看多/看空/暂不站队）。 */
    public static final List<String> CURRENT_SIDES = List.of("bull", "bear", "neutral");

    /** 有效期上限（自然日）：超过一个季度就不再是「可验证的判断」，而是空想。 */
    public static final int MAX_HORIZON_DAYS = 250;

    private static final java.util.regex.Pattern ISO_DATE =
            java.util.regex.Pattern.compile("^\\d{4}-\\d{2}-\\d{2}$");

    public JudgmentBlock {
        verificationRules = verificationRules == null ? List.of() : List.copyOf(verificationRules);
        logicTags = logicTags == null ? List.of() : List.copyOf(logicTags);
        dataGaps = dataGaps == null ? List.of() : List.copyOf(dataGaps);
    }

    /**
     * 解析判断块。
     *
     * @param raw      单元产出的判断块
     * @param degraded 是否降级判断（决定缺省 confidence/thesis 的处理）
     * @return 结构化视图
     * @throws ApiException 6001 任一字段形状不合法
     */
    public static JudgmentBlock parse(Map<String, Object> raw, boolean degraded) {
        Map<String, Object> block = raw == null ? Map.of() : raw;
        Direction direction = Direction.parse(block.get("direction"));
        Thesis thesis = Thesis.parse(block.get("thesis"), degraded);
        requireConsistentSide(direction.type(), thesis.currentSide());
        KeyLevels keyLevels = KeyLevels.parse(block.get("key_levels"), degraded);
        List<VerificationRule> verificationRules = parseRules(block.get("verification_rules"));
        validatePriceRules(keyLevels, verificationRules);
        return new JudgmentBlock(
                Identity.parse(block),
                direction,
                parseConfidence(block.get("confidence"), degraded),
                keyLevels,
                thesis,
                verificationRules,
                strings(block.get("logic_tags"), "$.logic_tags"),
                strings(block.get("data_gaps"), "$.data_gaps"));
    }

    // ================================================================ 嵌套值对象

    /** 单元身份：判断块自报的产出单元（存档器据此校验「块属于本次参与单元」）。 */
    public record Identity(String unitId, String unitVersion) {

        static Identity parse(Map<String, Object> block) {
            return new Identity(text(block.get("unit_id")), text(block.get("unit_version")));
        }

        /** 是否自报了身份（主判断块不一定自报，例如人工补录）。 */
        public boolean declared() {
            return unitId != null && unitVersion != null;
        }
    }

    /** 方向块：类型 + 有效期 + 到期日（可缺省，由存档器按有效期推算）+ 目标说明。 */
    public record Direction(String type, int horizonDays, String declaredExpireAt, String targetNote) {

        static Direction parse(Object raw) {
            Map<String, Object> direction = requireMap(raw, "$.direction");
            String type = requireDirectionType(direction.get("type"));
            int horizonDays = requireHorizonDays(direction.get("horizon_days"));
            String expireAt = text(direction.get("expire_at"));
            if (expireAt != null && !ISO_DATE.matcher(expireAt).matches()) {
                throw invalid("direction.expire_at 必须为 yyyy-MM-dd（当前 " + expireAt + "）");
            }
            return new Direction(type, horizonDays, expireAt, text(direction.get("target_note")));
        }
    }

    /** 关键价位：统一用不复权价格口径（能力详案 3.3 契约 6），缺失即空数组/ null。 */
    public record KeyLevels(List<BigDecimal> support, List<BigDecimal> resistance,
                            BigDecimal stopLoss, String invalidationCondition) {

        static KeyLevels parse(Object raw, boolean degraded) {
            if (raw == null) {
                if (degraded) {
                    return new KeyLevels(List.of(), List.of(), null, null);
                }
                throw invalid("$.key_levels 不能为空：正常判断必须声明关键价位与失效条件");
            }
            Map<String, Object> levels = requireMap(raw, "$.key_levels");
            return new KeyLevels(numbers(levels.get("support"), "$.key_levels.support"),
                    numbers(levels.get("resistance"), "$.key_levels.resistance"),
                    number(levels.get("stop_loss")), text(levels.get("invalidation_condition")));
        }
    }

    /** 多空论证：降级时必须补齐（否则主判断缺 thesis 字段，前端与归因都读不到）。 */
    public record Thesis(String bullCore, String bearCore, String currentSide, String confidence) {

        static Thesis parse(Object raw, boolean degraded) {
            if (raw == null && !degraded) {
                throw invalid("$.thesis 不能为空：正常判断必须同时给出看多、看空与当前占优方向");
            }
            Map<String, Object> thesis = raw == null ? Map.of() : requireMap(raw, "$.thesis");
            String fallback = degraded ? DEGRADED_THESIS : "未提供";
            String side = text(thesis.get("current_side"));
            if (side == null) {
                if (!degraded) {
                    throw invalid("$.thesis.current_side 不能为空");
                }
                side = "neutral";
            } else if (!CURRENT_SIDES.contains(side)) {
                throw invalid("thesis.current_side 必须为 " + String.join("/", CURRENT_SIDES) + "（当前 " + side + "）");
            }
            if (!degraded && (text(thesis.get("bull_core")) == null || text(thesis.get("bear_core")) == null)) {
                throw invalid("$.thesis.bull_core 与 bear_core 均不能为空：主判断必须同时保留正反论证");
            }
            String declaredConfidence = text(thesis.get("confidence"));
            String confidence = declaredConfidence == null
                    ? null
                    : requireConfidenceCode(declaredConfidence, "$.thesis.confidence");
            return new Thesis(textOrDefault(thesis.get("bull_core"), fallback),
                    textOrDefault(thesis.get("bear_core"), fallback),
                    side, confidence);
        }
    }

    /** 单条验证规则：{@code rule_type} 限四类，{@code on_hit} 缺省 {@code to_verify}。 */
    public record VerificationRule(String ruleId, String ruleType, String target,
                                   Map<String, Object> params, String onHit) {

        public VerificationRule {
            params = params == null ? Map.of() : Map.copyOf(params);
        }

        static VerificationRule parse(Object raw, int index) {
            String path = "$.verification_rules[" + index + "]";
            Map<String, Object> rule = requireMap(raw, path);
            String ruleType = text(rule.get("rule_type"));
            if (ruleType == null || !RULE_TYPES.contains(ruleType)) {
                throw invalid(path + ".rule_type 必须为 " + String.join("/", RULE_TYPES)
                        + "（当前 " + ruleType + "）");
            }
            Map<String, Object> params = rule.get("params") instanceof Map<?, ?> map
                    ? copyOf(map)
                    : Map.of();
            String onHit = text(rule.get("on_hit"));
            if (onHit != null && !"to_verify".equals(onHit)) {
                throw invalid(path + ".on_hit 当前仅支持 to_verify（M3 再扩展 hit/miss/review）");
            }
            String declaredRuleId = text(rule.get("rule_id"));
            return new VerificationRule(declaredRuleId == null ? "R" + (index + 1) : declaredRuleId,
                    ruleType, text(rule.get("target")), params,
                    onHit == null ? "to_verify" : onHit);
        }
    }

    // ================================================================ 校验与取值

    private static List<VerificationRule> parseRules(Object raw) {
        if (!(raw instanceof List<?> rules) || rules.isEmpty()) {
            throw invalid("verification_rules 至少 1 条：判断必须可被流水线3 巡检（3.3 契约 1）");
        }
        List<VerificationRule> parsed = new ArrayList<>(rules.size());
        Set<String> ruleIds = new LinkedHashSet<>();
        for (int i = 0; i < rules.size(); i++) {
            VerificationRule rule = VerificationRule.parse(rules.get(i), i);
            if (!ruleIds.add(rule.ruleId())) {
                throw invalid("$.verification_rules[" + i + "].rule_id 重复：" + rule.ruleId());
            }
            if ("logic_invalid".equals(rule.ruleType()) && !hasValues(rule.params(), "watch_tags")
                    && !hasValues(rule.params(), "event_types")) {
                throw invalid("$.verification_rules[" + i + "] logic_invalid 至少需要 watch_tags 或 event_types");
            }
            parsed.add(rule);
        }
        return parsed;
    }

    private static void validatePriceRules(KeyLevels levels, List<VerificationRule> rules) {
        for (int i = 0; i < rules.size(); i++) {
            VerificationRule rule = rules.get(i);
            if (!"price_touch".equals(rule.ruleType()) && !"price_break".equals(rule.ruleType())) {
                continue;
            }
            List<BigDecimal> candidates = switch (rule.target() == null ? "" : rule.target()) {
                case "support" -> levels.support();
                case "resistance" -> levels.resistance();
                case "stop_loss" -> levels.stopLoss() == null ? List.of() : List.of(levels.stopLoss());
                default -> List.of();
            };
            BigDecimal level = number(rule.params().get("level"));
            if (level == null || candidates.isEmpty()
                    || candidates.stream().noneMatch(candidate -> withinPriceTolerance(candidate, level))) {
                throw invalid("$.verification_rules[" + i + "].params.level 必须与 key_levels."
                        + rule.target() + " 中的某个价位一致（允许偏差 ≤0.5%）");
            }
            if ("stop_loss".equals(rule.target()) && !"price_break".equals(rule.ruleType())) {
                throw invalid("$.verification_rules[" + i + "] stop_loss 只允许 price_break（收盘确认）");
            }
        }
    }

    private static boolean withinPriceTolerance(BigDecimal expected, BigDecimal declared) {
        BigDecimal tolerance = expected.abs().multiply(new BigDecimal("0.005"));
        return expected.subtract(declared).abs().compareTo(tolerance) <= 0;
    }

    private static String parseConfidence(Object raw, boolean degraded) {
        String code = text(raw);
        if (code == null) {
            if (degraded) {
                return "低";
            }
            throw invalid("confidence 不能为空（正常判断必须给出置信度）");
        }
        return requireConfidenceCode(code, "$.confidence");
    }

    private static String requireConfidenceCode(String code, String path) {
        try {
            return Enums.requireCode(JudgmentConfidenceEnum.class, code).code();
        } catch (ApiException e) {
            throw invalid(path + " 取值非法：" + e.getMessage());
        }
    }

    private static void requireConsistentSide(String direction, String side) {
        String expected = switch (direction) {
            case "bullish" -> "bull";
            case "bearish" -> "bear";
            case "neutral", "conditional" -> "neutral";
            default -> throw invalid("$.direction.type 取值非法：" + direction);
        };
        if (!expected.equals(side)) {
            throw invalid("$.thesis.current_side 与 direction.type 不一致：" + direction + " 应对应 " + expected
                    + "，当前 " + side);
        }
    }

    private static String requireDirectionType(Object raw) {
        String code = text(raw);
        if (code == null) {
            throw invalid("$.direction.type 不能为空");
        }
        try {
            return Enums.requireCode(JudgmentDirectionEnum.class, code).code();
        } catch (ApiException e) {
            throw invalid("$.direction.type 取值非法：" + e.getMessage());
        }
    }

    private static int requireHorizonDays(Object raw) {
        BigDecimal value = number(raw);
        if (value == null) {
            throw invalid("direction.horizon_days 必须是数字（当前 " + raw + "）");
        }
        int days;
        try {
            days = value.intValueExact();
        } catch (ArithmeticException e) {
            throw invalid("direction.horizon_days 必须为整数交易日（当前 " + raw + "）");
        }
        if (days < 1 || days > MAX_HORIZON_DAYS) {
            throw invalid("direction.horizon_days 必须为 1~" + MAX_HORIZON_DAYS + "（当前 " + days + "）");
        }
        return days;
    }

    private static Map<String, Object> requireMap(Object raw, String path) {
        if (!(raw instanceof Map<?, ?> map)) {
            throw invalid(path + " 必须是对象（当前 " + raw + "）");
        }
        return copyOf(map);
    }

    private static Map<String, Object> copyOf(Map<?, ?> map) {
        Map<String, Object> copy = new LinkedHashMap<>();
        map.forEach((key, value) -> copy.put(String.valueOf(key), value));
        return copy;
    }

    private static List<BigDecimal> numbers(Object raw, String path) {
        if (raw == null) {
            return List.of();
        }
        if (!(raw instanceof List<?> values)) {
            throw invalid(path + " 必须是数组");
        }
        List<BigDecimal> numbers = new ArrayList<>(values.size());
        for (Object value : values) {
            BigDecimal number = number(value);
            if (number == null) {
                throw invalid(path + " 只能是数字（当前 " + value + "）");
            }
            numbers.add(number);
        }
        return numbers;
    }

    private static List<String> strings(Object raw, String path) {
        if (raw == null) {
            return List.of();
        }
        if (!(raw instanceof List<?> values)) {
            throw invalid(path + " 必须是数组");
        }
        List<String> result = new ArrayList<>(values.size());
        for (Object value : values) {
            String text = text(value);
            if (text != null) {
                result.add(text);
            }
        }
        return result;
    }

    private static boolean hasValues(Map<String, Object> params, String key) {
        return params.get(key) instanceof List<?> values && !values.isEmpty();
    }

    private static BigDecimal number(Object raw) {
        if (raw instanceof BigDecimal decimal) {
            return decimal;
        }
        if (raw instanceof Number number) {
            return new BigDecimal(number.toString());
        }
        String text = text(raw);
        if (text == null) {
            return null;
        }
        try {
            return new BigDecimal(text);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static String text(Object raw) {
        return raw == null ? null : Strings.trimToNull(String.valueOf(raw));
    }

    private static String textOrDefault(Object raw, String fallback) {
        String text = text(raw);
        return text == null ? fallback : text;
    }

    private static ApiException invalid(String message) {
        return ApiException.of(JudgmentErrorCode.PAYLOAD_INVALID, message);
    }
}
