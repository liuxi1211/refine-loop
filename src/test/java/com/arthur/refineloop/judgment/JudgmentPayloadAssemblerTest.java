package com.arthur.refineloop.judgment;

import com.arthur.refineloop.common.ApiException;
import com.arthur.refineloop.judgment.model.AssembledJudgment;
import com.arthur.refineloop.judgment.model.Benchmark;
import com.arthur.refineloop.judgment.model.JudgmentArchiveRequest;
import com.arthur.refineloop.judgment.support.JudgmentFixtures;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowableOfType;

/**
 * 主判断 payload 组装：字段形状、降级补齐、口径校验。
 *
 * <p>断言直接对着能力详案 3.2 的字段名——这份 JSON 是 M3 归因的输入契约，字段改名等于换契约，
 * 必须让测试先红。
 */
@DisplayName("JudgmentPayloadAssembler：主判断 Schema 组装")
class JudgmentPayloadAssemblerTest {

    private static final Clock CLOCK =
            Clock.fixed(Instant.parse("2026-11-26T11:00:00Z"), ZoneId.of("Asia/Shanghai"));

    private final JudgmentPayloadAssembler assembler = new JudgmentPayloadAssembler();

    @Test
    @DisplayName("正常判断：身份/基准/方法/方向/正文逐块齐全，created_at 带时区偏移")
    void assemblesNormalJudgment() {
        JudgmentArchiveRequest request = JudgmentFixtures.request(7L);

        AssembledJudgment assembled = assembler.assemble(request, context("J-20261126-P1-0001", false));

        Map<String, Object> payload = assembled.payload();
        assertThat(payload).containsEntry("judgment_id", "J-20261126-P1-0001")
                .containsEntry("stock_code", JudgmentFixtures.TS_CODE)
                .containsEntry("stock_name", "平安银行")
                .containsEntry("pipeline_id", "P1")
                .containsEntry("trade_date", "20261126")
                .containsEntry("degraded", false)
                .containsEntry("report_ref", "reports/20261126/000001.SZ.md")
                .containsEntry("created_at", "2026-11-26T19:00:00+08:00");
        assertThat(assembled.direction()).isEqualTo("bullish");
        assertThat(assembled.horizonDays()).isEqualTo(60);
        assertThat(assembled.expireAt()).isEqualTo("2027-01-25");
        assertThat(assembled.confidence()).isEqualTo("中");

        @SuppressWarnings("unchecked")
        Map<String, Object> method = (Map<String, Object>) payload.get("method");
        assertThat(method).containsEntry("method_version", "M1-v1.0").containsEntry("school", "growth_trend");
        assertThat(method.get("units_used")).asInstanceOf(
                        org.assertj.core.api.InstanceOfAssertFactories.LIST)
                .containsExactly("calc_valuation_percentile@v1.0", "interpret_valuation_probe@v1.0");

        @SuppressWarnings("unchecked")
        Map<String, Object> benchmark = (Map<String, Object>) payload.get("benchmark");
        assertThat(benchmark).containsEntry("index_code", "000300.SH").containsEntry("industry_code", "801780.SI");

        @SuppressWarnings("unchecked")
        Map<String, Object> direction = (Map<String, Object>) payload.get("direction");
        assertThat(direction).containsEntry("type", "bullish")
                .containsEntry("horizon_days", 60)
                .containsEntry("expire_at", "2027-01-25")
                .containsEntry("is_reaffirm", false)
                .containsEntry("target_note", "预期相对沪深300 获取超额收益");
        assertThat(direction).containsKey("scale");

        @SuppressWarnings("unchecked")
        Map<String, Object> thesis = (Map<String, Object>) payload.get("thesis");
        assertThat(thesis).containsEntry("current_side", "bull")
                .containsEntry("confidence", "中")
                .containsEntry("logic_tags", List.of("估值处于历史低位"));

        @SuppressWarnings("unchecked")
        Map<String, Object> keyLevels = (Map<String, Object>) payload.get("key_levels");
        assertThat(keyLevels.get("support")).asInstanceOf(
                        org.assertj.core.api.InstanceOfAssertFactories.LIST)
                .containsExactly(new java.math.BigDecimal("10.85"));
        assertThat(keyLevels).containsEntry("stop_loss", new java.math.BigDecimal("10.4"));
    }

    @Test
    @DisplayName("方向缺 expire_at：按有效期自然日推算（trade_date + horizon_days）")
    void computesExpireAtFromHorizon() {
        Map<String, Object> block = JudgmentFixtures.copyOf(JudgmentFixtures.block());
        @SuppressWarnings("unchecked")
        Map<String, Object> direction = (Map<String, Object>) block.get("direction");
        direction.remove("target_note");

        AssembledJudgment assembled = assembler.assemble(
                JudgmentFixtures.request(JudgmentFixtures.TRADE_DATE, JudgmentFixtures.TS_CODE, block,
                        false, List.of(), JudgmentFixtures.method(), 7L),
                context("J-20261126-P1-0001", false));

        assertThat(assembled.expireAt()).isEqualTo("2027-01-25");
    }

    @Test
    @DisplayName("重申标记原样进 payload（M3 靠它剔除重复样本）")
    void carriesReaffirmFlag() {
        AssembledJudgment assembled = assembler.assemble(JudgmentFixtures.request(7L),
                context("J-20261127-P1-0003", true));

        @SuppressWarnings("unchecked")
        Map<String, Object> direction = (Map<String, Object>) assembled.payload().get("direction");
        assertThat(direction).containsEntry("is_reaffirm", true);
    }

    @Test
    @DisplayName("降级判断：thesis 机械补齐、key_levels 空对象、confidence 低——与正常判断同一条路径")
    void assemblesDegradedJudgment() {
        AssembledJudgment assembled = assembler.assemble(JudgmentFixtures.degradedRequest(9L),
                context("J-20261126-P1-0002", false));

        Map<String, Object> payload = assembled.payload();
        assertThat(payload).containsEntry("degraded", true);
        assertThat(assembled.direction()).isEqualTo("neutral");
        assertThat(assembled.expireAt()).isEqualTo("2026-12-03");
        assertThat(assembled.confidence()).isEqualTo("低");

        @SuppressWarnings("unchecked")
        Map<String, Object> thesis = (Map<String, Object>) payload.get("thesis");
        assertThat(thesis).containsEntry("current_side", "neutral")
                .containsEntry("bull_core", JudgmentPayloadAssembler.DEGRADED_THESIS)
                .containsEntry("confidence", "低");
        assertThat(thesis.get("logic_tags")).asInstanceOf(
                org.assertj.core.api.InstanceOfAssertFactories.LIST).isEmpty();

        @SuppressWarnings("unchecked")
        Map<String, Object> keyLevels = (Map<String, Object>) payload.get("key_levels");
        // 降级判断没有价位事实：四个键仍然齐全（前端按固定形状渲染），但值是空数组 / null / 空串
        assertThat(keyLevels).containsOnlyKeys("support", "resistance", "stop_loss", "invalidation_condition");
        assertThat(keyLevels.get("support")).asInstanceOf(
                org.assertj.core.api.InstanceOfAssertFactories.LIST).isEmpty();
        assertThat(keyLevels.get("resistance")).asInstanceOf(
                org.assertj.core.api.InstanceOfAssertFactories.LIST).isEmpty();
        assertThat(keyLevels).containsEntry("stop_loss", null).containsEntry("invalidation_condition", "");

        assertThat(payload.get("data_gaps")).asInstanceOf(
                        org.assertj.core.api.InstanceOfAssertFactories.LIST)
                .containsExactly("L3 校验重试耗尽：正文出现未包裹数字");
    }

    @Test
    @DisplayName("data_gaps 合并请求侧与判断块侧并去重，吞掉占位「无」")
    void mergesDataGaps() {
        Map<String, Object> block = JudgmentFixtures.copyOf(JudgmentFixtures.block());
        block.put("data_gaps", List.of("3 年前数据缺失 2 个月", "无"));

        AssembledJudgment assembled = assembler.assemble(
                JudgmentFixtures.request(JudgmentFixtures.TRADE_DATE, JudgmentFixtures.TS_CODE, block,
                        false, List.of("3 年前数据缺失 2 个月", "行业归属缺失"),
                        JudgmentFixtures.method(), 7L),
                context("J-20261126-P1-0001", false));

        assertThat(assembled.payload().get("data_gaps")).asInstanceOf(
                        org.assertj.core.api.InstanceOfAssertFactories.LIST)
                .containsExactly("3 年前数据缺失 2 个月", "行业归属缺失");
    }

    @Test
    @DisplayName("基准行业缺失：industry_code 写空串（payload 不留 null），由 data_gaps 说明")
    void writesEmptyIndustryCodeWhenMissing() {
        AssembledJudgment assembled = assembler.assemble(JudgmentFixtures.request(7L),
                new JudgmentPayloadAssembler.AssemblyContext("J-20261126-P1-0001", false,
                        new Benchmark("000300.SH", null), "平安银行", offsetNow()));

        @SuppressWarnings("unchecked")
        Map<String, Object> benchmark = (Map<String, Object>) assembled.payload().get("benchmark");
        assertThat(benchmark).containsEntry("industry_code", "");
    }

    // ================================================================ 校验

    @Test
    @DisplayName("verification_rules 为空 → 6001（判断必须可被流水线3 巡检）")
    void rejectsEmptyVerificationRules() {
        Map<String, Object> block = JudgmentFixtures.copyOf(JudgmentFixtures.block());
        block.put("verification_rules", List.of());

        assertThat(codeOf(block, false)).isEqualTo(6001);
    }

    @Test
    @DisplayName("verification_rules 出现第五类规则 → 6001")
    void rejectsUnknownRuleType() {
        Map<String, Object> block = JudgmentFixtures.copyOf(JudgmentFixtures.block());
        block.put("verification_rules", List.of(Map.of("rule_type", "macro_shift")));

        assertThat(codeOf(block, false)).isEqualTo(6001);
    }

    @Test
    @DisplayName("方向越界 / 有效期越界 / 到期日早于交易日 → 6001")
    void rejectsInvalidDirection() {
        Map<String, Object> badType = JudgmentFixtures.copyOf(JudgmentFixtures.block());
        directionOf(badType).put("type", "strong_buy");
        assertThat(codeOf(badType, false)).isEqualTo(6001);

        Map<String, Object> badHorizon = JudgmentFixtures.copyOf(JudgmentFixtures.block());
        directionOf(badHorizon).put("horizon_days", 0);
        assertThat(codeOf(badHorizon, false)).isEqualTo(6001);

        Map<String, Object> badExpire = JudgmentFixtures.copyOf(JudgmentFixtures.block());
        directionOf(badExpire).put("expire_at", "2026-11-01");
        assertThat(codeOf(badExpire, false)).isEqualTo(6001);
    }

    @Test
    @DisplayName("正常判断缺 confidence → 6001（降级才允许缺省补「低」）")
    void rejectsMissingConfidenceForNormalJudgment() {
        Map<String, Object> block = JudgmentFixtures.copyOf(JudgmentFixtures.block());
        block.remove("confidence");

        assertThat(codeOf(block, false)).isEqualTo(6001);
    }

    @Test
    @DisplayName("置信度取值非法 → 6011 之外的 6001（统一出口）")
    void rejectsInvalidConfidence() {
        Map<String, Object> block = JudgmentFixtures.copyOf(JudgmentFixtures.block());
        block.put("confidence", "很高");

        assertThat(codeOf(block, false)).isEqualTo(6001);
    }

    @Test
    @DisplayName("判断块自报单元不在 method.units 中 → 6001（防串线污染归因）")
    void rejectsUnitOutsideMethodSnapshot() {
        Map<String, Object> block = JudgmentFixtures.copyOf(JudgmentFixtures.block());
        block.put("unit_id", "interpret_other");
        block.put("unit_version", "v2.0");

        assertThat(codeOf(block, false)).isEqualTo(6001);
    }

    @Test
    @DisplayName("reportId 非正 → 6001（降级判断也必须有占位报告）")
    void rejectsNonPositiveReportId() {
        ApiException error = catchThrowableOfType(() -> assembler.planDirection(
                        JudgmentFixtures.request(0L)), ApiException.class);

        assertThat(error).isNotNull();
        assertThat(error.getCode()).isEqualTo(6001);
        assertThat(error.getMessage()).contains("reportId");
    }

    // ================================================================ 夹具

    private int codeOf(Map<String, Object> block, boolean degraded) {
        ApiException error = catchThrowableOfType(() -> assembler.assemble(
                JudgmentFixtures.request(JudgmentFixtures.TRADE_DATE, JudgmentFixtures.TS_CODE, block,
                        degraded, List.of(), JudgmentFixtures.method(), 7L),
                context("J-20261126-P1-0001", false)), ApiException.class);
        assertThat(error).as("期望校验失败，但组装通过了").isNotNull();
        return error.getCode();
    }

    private JudgmentPayloadAssembler.AssemblyContext context(String judgmentId, boolean reaffirm) {
        return new JudgmentPayloadAssembler.AssemblyContext(judgmentId, reaffirm,
                new Benchmark("000300.SH", "801780.SI"), "平安银行", offsetNow());
    }

    private OffsetDateTime offsetNow() {
        return OffsetDateTime.now(CLOCK).truncatedTo(java.time.temporal.ChronoUnit.SECONDS);
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> directionOf(Map<String, Object> block) {
        return (Map<String, Object>) block.get("direction");
    }
}
