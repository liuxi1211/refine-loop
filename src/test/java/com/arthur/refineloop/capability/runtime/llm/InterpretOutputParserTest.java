package com.arthur.refineloop.capability.runtime.llm;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("InterpretOutputParser：报告 + json 围栏拆解")
class InterpretOutputParserTest {

    @Test
    @DisplayName("拆分报告与判断块，报告保留围栏之前的内容")
    void splitsReportAndBlock() {
        String raw = """
                ## 结论
                估值偏低。

                ```json
                {"unit_id": "interpret_reference", "confidence": "中"}
                ```
                """;

        UnitRawOutput output = InterpretOutputParser.parse(raw);

        assertThat(output.judgmentParseError()).isNull();
        assertThat(output.hasJudgmentBlock()).isTrue();
        assertThat(output.judgmentBlock()).containsEntry("confidence", "中");
        assertThat(output.reportMd()).startsWith("## 结论").contains("估值偏低。");
        assertThat(output.reportMd()).doesNotContain("```json");
        assertThat(output.rawText()).isEqualTo(raw);
    }

    @Test
    @DisplayName("取最后一个 json 围栏（容忍正文里的示例块）")
    void takesLastFence() {
        String raw = """
                示例格式如下：
                ```json
                {"example": true}
                ```
                正式结论：

                ```json
                {"unit_id": "interpret_reference", "conclusion": "偏低"}
                ```
                """;

        UnitRawOutput output = InterpretOutputParser.parse(raw);

        assertThat(output.judgmentBlock()).containsEntry("conclusion", "偏低");
        assertThat(output.judgmentBlock()).doesNotContainKey("example");
        assertThat(InterpretOutputParser.fenceStarts(raw)).hasSize(2);
    }

    @Test
    @DisplayName("无围栏但整段是 JSON 对象：报告为空、块可用")
    void acceptsBareJsonObject() {
        UnitRawOutput output = InterpretOutputParser.parse("{\"unit_id\":\"interpret_reference\"}");

        assertThat(output.judgmentParseError()).isNull();
        assertThat(output.reportMd()).isEmpty();
        assertThat(output.judgmentBlock()).containsEntry("unit_id", "interpret_reference");
    }

    @Test
    @DisplayName("无 json 围栏：保留报告并给出解析错误")
    void reportsMissingFence() {
        UnitRawOutput output = InterpretOutputParser.parse("## 结论\n估值偏低，但没有给判断块。");

        assertThat(output.judgmentParseError()).contains("未找到");
        assertThat(output.reportMd()).contains("估值偏低");
        assertThat(output.hasJudgmentBlock()).isFalse();
    }

    @Test
    @DisplayName("围栏内不是合法 JSON：给出解析错误并保留报告")
    void reportsInvalidJson() {
        String raw = """
                ## 结论
                估值偏低。
                ```json
                {"unit_id": "interpret_reference", "confidence": }
                ```
                """;

        UnitRawOutput output = InterpretOutputParser.parse(raw);

        assertThat(output.judgmentParseError()).contains("不是合法 JSON");
        assertThat(output.reportMd()).contains("估值偏低");
    }

    @Test
    @DisplayName("空围栏与空返回都被判为解析失败")
    void reportsEmptyInputs() {
        assertThat(InterpretOutputParser.parse("").judgmentParseError()).contains("返回为空");
        assertThat(InterpretOutputParser.parse(null).judgmentParseError()).contains("返回为空");
        assertThat(InterpretOutputParser.parse("```json\n   \n```").judgmentParseError())
                .contains("json 代码块为空");
    }

    @Test
    @DisplayName("Unparsed 工厂：块为空、错误原因保留")
    void unparsedFactoryKeepsReason() {
        UnitRawOutput output = UnitRawOutput.unparsed("raw", "report", "boom");

        assertThat(output.judgmentParseError()).isEqualTo("boom");
        assertThat(output.judgmentBlock()).isEqualTo(Map.of());
        assertThat(output.reportMd()).isEqualTo("report");
    }
}
