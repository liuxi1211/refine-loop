package com.arthur.refineloop.capability.runtime.validation.validators;

import com.arthur.refineloop.capability.runtime.validation.ValidationError;
import com.arthur.refineloop.capability.support.JudgmentBlocks;
import com.arthur.refineloop.capability.support.ReferenceFacts;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("ReportNumberScanner：正文数字溯源")
class ReportNumberScannerTest {

    private final ReportNumberScanner scanner = new ReportNumberScanner();

    @Test
    @DisplayName("全部数字带标记时通过（含标题序号、日期、版本号等非事实数字）")
    void acceptsFullyTracedReport() {
        String report = """
                ## 1. 结论先行
                估值分位为 %s，处于历史低位；PB 分位 %s。
                报告基于 2026年11月26日 数据，方法版本 v1.0，流水线 P1 / 阶段 S3。
                1. 第一步：口径适配
                2. 第二步：位置判断
                """.formatted(
                marker(ReferenceFacts.PE_PERCENTILE, "18.5"),
                marker(ReferenceFacts.PB_PERCENTILE, "22.1"));

        assertThat(scanner.validate(context(report))).isEmpty();
    }

    @Test
    @DisplayName("正文裸数字失败，错误带行号与上下文")
    void rejectsBareNumber() {
        String report = """
                ## 一、结论先行
                当前 PE 分位 18.5，属于历史低位。
                """;

        List<ValidationError> errors = scanner.validate(context(report));

        assertThat(errors).hasSize(1);
        assertThat(errors.get(0).rule()).isEqualTo("bare_number");
        assertThat(errors.get(0).path()).isEqualTo("report:2");
        assertThat(errors.get(0).message()).contains("18.5").contains("[[指标|数值]]");
    }

    @Test
    @DisplayName("标记引用了 facts_used 之外的指标 → marker_metric_unknown")
    void rejectsMarkerWithUnknownMetric() {
        String report = "分位为 " + marker("valuation.percentile_3y.ps_ttm", "35.0") + "。";

        List<ValidationError> errors = scanner.validate(context(report));

        assertThat(errors).hasSize(1);
        assertThat(errors.get(0).rule()).isEqualTo("marker_metric_unknown");
        assertThat(errors.get(0).message()).contains("ps_ttm");
    }

    @Test
    @DisplayName("标记数值与 facts_used 不一致 → marker_value_mismatch")
    void rejectsMarkerValueMismatch() {
        String report = "分位为 " + marker(ReferenceFacts.PE_PERCENTILE, "26.0") + "。";

        List<ValidationError> errors = scanner.validate(context(report));

        assertThat(errors).hasSize(1);
        assertThat(errors.get(0).rule()).isEqualTo("marker_value_mismatch");
    }

    @Test
    @DisplayName("标记数值非数字 → marker_value_not_numeric")
    void rejectsNonNumericMarkerValue() {
        String report = "分位为 " + marker(ReferenceFacts.PE_PERCENTILE, "偏低") + "。";

        List<ValidationError> errors = scanner.validate(context(report));

        assertThat(errors).hasSize(1);
        assertThat(errors.get(0).rule()).isEqualTo("marker_value_not_numeric");
    }

    @Test
    @DisplayName("代码块与行内代码里的数字不算正文数字")
    void ignoresNumbersInCode() {
        String report = """
                示例：
                ```json
                {"pe_ttm": 18.5}
                ```
                参考 `percentile(18.5)` 的实现说明。
                """;

        assertThat(scanner.validate(context(report))).isEmpty();
    }

    @Test
    @DisplayName("表格里的裸数字同样失败")
    void rejectsBareNumberInTable() {
        String report = """
                | 指标 | 数值 |
                | --- | --- |
                | PE 分位 | 18.5 |
                """;

        List<ValidationError> errors = scanner.validate(context(report));

        assertThat(errors).extracting(ValidationError::rule).contains("bare_number");
        assertThat(errors).allSatisfy(error -> assertThat(error.message()).contains("18.5"));
    }

    @Test
    @DisplayName("超过上限时只报汇总，不刷满回喂文本")
    void truncatesExcessiveBareNumbers() {
        StringBuilder report = new StringBuilder();
        for (int i = 0; i < 30; i++) {
            report.append("第 ").append(i).append(" 行数据 ").append(100 + i).append("\n");
        }

        List<ValidationError> errors = scanner.validate(context(report.toString()));

        assertThat(errors.size()).isLessThanOrEqualTo(ReportNumberScanner.MAX_BARE_NUMBER_ERRORS + 1);
        assertThat(errors).extracting(ValidationError::rule).contains("bare_number_truncated");
    }

    @Test
    @DisplayName("空报告直接通过（正文缺失由 Schema/样本回放兜）")
    void emptyReportPasses() {
        assertThat(scanner.validate(context(""))).isEmpty();
    }

    private String marker(String metric, String value) {
        return JudgmentBlocks.marker(metric, value);
    }

    private com.arthur.refineloop.capability.runtime.validation.ValidationContext context(String report) {
        return JudgmentBlocks.context(JudgmentBlocks.base(), report);
    }
}
