package com.arthur.refineloop.capability.runtime;

import com.arthur.refineloop.common.ApiException;
import com.arthur.refineloop.data.mapper.TushareLookupMapper;
import com.arthur.refineloop.support.SchemaTestSupport;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowableOfType;

@DisplayName("TradeCalTradingDayCalendar：交易日顺延")
class TradeCalTradingDayCalendarTest {

    private JdbcTemplate jdbc;
    private TradeCalTradingDayCalendar calendar;

    @BeforeEach
    void setUp() {
        jdbc = SchemaTestSupport.newJdbcTemplate();
        calendar = new TradeCalTradingDayCalendar(SchemaTestSupport.mapper(jdbc, TushareLookupMapper.class));
    }

    @Test
    @DisplayName("跳过周末：周五 + 1 个交易日 = 下周一")
    void skipsWeekend() {
        openDays("20261127", "20261130", "20261201");

        assertThat(calendar.plusTradingDays("20261126", 1)).isEqualTo("20261127");
        assertThat(calendar.plusTradingDays("20261127", 1)).isEqualTo("20261130");
    }

    @Test
    @DisplayName("第 5 个交易日：跨周末与假期取倒数第 N 个")
    void fifthTradingDayAcrossHoliday() {
        // 20261127(五) 20261130(一) 20261201 20261202 20261203 20261204
        openDays("20261127", "20261130", "20261201", "20261202", "20261203", "20261204");

        assertThat(calendar.plusTradingDays("20261126", 5)).isEqualTo("20261203");
    }

    @Test
    @DisplayName("SSE 日历缺失时退回任意交易所")
    void fallsBackToAnyExchange() {
        jdbc.update("INSERT INTO trade_cal (exchange, cal_date, is_open) VALUES ('SZSE', '20261127', '1')");

        assertThat(calendar.plusTradingDays("20261126", 1)).isEqualTo("20261127");
    }

    @Test
    @DisplayName("休市日不算交易日")
    void closedDaysIgnored() {
        jdbc.update("INSERT INTO trade_cal (exchange, cal_date, is_open) VALUES ('SSE', '20261127', '0')");
        openDays("20261130");

        assertThat(calendar.plusTradingDays("20261126", 1)).isEqualTo("20261130");
    }

    @Test
    @DisplayName("日历不足抛 1003（不猜自然日）")
    void insufficientCalendarRaisesDataNotReady() {
        openDays("20261127");

        ApiException error = catchThrowableOfType(
                () -> calendar.plusTradingDays("20261126", 5), ApiException.class);

        assertThat(error).isNotNull();
        assertThat(error.getCode()).isEqualTo(1003);
        assertThat(error.getMessage()).contains("交易日历不足");
    }

    @Test
    @DisplayName("非法入参抛 400")
    void rejectsInvalidInput() {
        ApiException badDate = catchThrowableOfType(
                () -> calendar.plusTradingDays("2026-11-26", 1), ApiException.class);
        ApiException badCount = catchThrowableOfType(
                () -> calendar.plusTradingDays("20261126", 0), ApiException.class);

        assertThat(badDate).isNotNull();
        assertThat(badDate.getCode()).isEqualTo(400);
        assertThat(badCount).isNotNull();
        assertThat(badCount.getCode()).isEqualTo(400);
    }

    private void openDays(String... dates) {
        for (String date : dates) {
            jdbc.update("INSERT INTO trade_cal (exchange, cal_date, is_open) VALUES ('SSE', ?, '1')", date);
        }
    }
}
