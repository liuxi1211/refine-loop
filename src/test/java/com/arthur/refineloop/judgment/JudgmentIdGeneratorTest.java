package com.arthur.refineloop.judgment;

import com.arthur.refineloop.common.ApiException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowableOfType;

@DisplayName("判断编号生成：J-{date}-{pipeline}-{seq}")
class JudgmentIdGeneratorTest {

    private final JudgmentIdGenerator generator = new JudgmentIdGenerator();

    @Test
    @DisplayName("首份判断从 0001 开始")
    void startsAtOne() {
        assertThat(generator.next("20261126", "P1", null)).isEqualTo("J-20261126-P1-0001");
        assertThat(generator.next("20261126", "P1", "   ")).isEqualTo("J-20261126-P1-0001");
    }

    @Test
    @DisplayName("序号按现有最新编号递增，且保持 4 位补零（字典序 = 数值序）")
    void incrementsFromLatestId() {
        assertThat(generator.next("20261126", "P1", "J-20261126-P1-0009"))
                .isEqualTo("J-20261126-P1-0010");
        assertThat(generator.next("20261126", "P1", "J-20261126-P1-0099"))
                .isEqualTo("J-20261126-P1-0100");
        assertThat(generator.next("20261126", "P1", "J-20261126-P1-0999"))
                .isEqualTo("J-20261126-P1-1000");
    }

    @Test
    @DisplayName("不同流水线/日期各自独立：编号里带日期与流水线")
    void carriesDateAndPipeline() {
        assertThat(generator.next("20261127", "P1", "J-20261126-P1-0042"))
                .isEqualTo("J-20261127-P1-0001");
        assertThat(generator.next("20261126", "P2", "J-20261126-P1-0042"))
                .isEqualTo("J-20261126-P2-0001");
        assertThat(generator.next("20261126", "P1-M", "J-20261126-P1-0042"))
                .isEqualTo("J-20261126-P1-M-0001");
    }

    @Test
    @DisplayName("现有编号无法解析序号 → 6001（数据异常必须暴露，不能静默从 1 再来一遍）")
    void rejectsUnparsableLatestId() {
        ApiException error = catchThrowableOfType(
                () -> generator.next("20261126", "P1", "J-20261126-P1-ABC"), ApiException.class);

        assertThat(error).isNotNull();
        assertThat(error.getCode()).isEqualTo(6001);
        assertThat(error.getMessage()).contains("序号");
    }

    @Test
    @DisplayName("序号超过 9999 → 6004")
    void rejectsSequenceOverflow() {
        ApiException error = catchThrowableOfType(
                () -> generator.next("20261126", "P1", "J-20261126-P1-9999"), ApiException.class);

        assertThat(error).isNotNull();
        assertThat(error.getCode()).isEqualTo(6004);
    }

    @Test
    @DisplayName("交易日/流水线格式非法 → 6001")
    void rejectsInvalidIdentity() {
        assertThat(catchThrowableOfType(() -> generator.next("2026-11-26", "P1", null), ApiException.class)
                .getCode()).isEqualTo(6001);
        assertThat(catchThrowableOfType(() -> generator.next("20261126", "p1", null), ApiException.class)
                .getCode()).isEqualTo(6001);
        assertThat(catchThrowableOfType(() -> generator.next("20261126", "PIPELINE_TOO_LONG", null),
                ApiException.class).getCode()).isEqualTo(6001);
    }
}
