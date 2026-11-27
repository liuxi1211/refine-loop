package com.arthur.refineloop.judgment;

import com.arthur.refineloop.common.ApiException;
import com.arthur.refineloop.data.mapper.TushareLookupMapper;
import com.arthur.refineloop.support.SchemaTestSupport;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class JudgmentExpiryCalculatorTest {

    @Test
    void computesNthTradingDayAcrossWeekendAndHolidays() {
        JdbcTemplate jdbc = SchemaTestSupport.newJdbcTemplate();
        // 11/26 周四开市，11/27 周五与 11/30~12/03 开市，周末休市
        for (String date : new String[]{"20261126", "20261127", "20261130", "20261201", "20261202", "20261203"}) {
            jdbc.update("INSERT INTO trade_cal (exchange, cal_date, is_open) VALUES ('SSE', ?, '1')", date);
        }
        JudgmentExpiryCalculator calculator = new JudgmentExpiryCalculator(
                SchemaTestSupport.mapper(jdbc, TushareLookupMapper.class));

        assertThat(calculator.plusTradingDays("20261126", 1)).isEqualTo("20261127");
        assertThat(calculator.plusTradingDays("20261126", 5)).isEqualTo("20261203");
    }

    @Test
    void rejectsInsteadOfUsingNaturalDayFallbackWhenCalendarIsInsufficient() {
        JdbcTemplate jdbc = SchemaTestSupport.newJdbcTemplate();
        LocalDate start = LocalDate.of(2026, 11, 26);
        for (int i = 1; i <= 20; i++) {
            jdbc.update("INSERT INTO trade_cal (exchange, cal_date, is_open) VALUES ('SSE', ?, '1')",
                    start.plusDays(i).format(java.time.format.DateTimeFormatter.BASIC_ISO_DATE));
        }
        JudgmentExpiryCalculator calculator = new JudgmentExpiryCalculator(
                SchemaTestSupport.mapper(jdbc, TushareLookupMapper.class));

        assertThatThrownBy(() -> calculator.plusTradingDays("20261126", 60))
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("交易日历不足");
    }
}
