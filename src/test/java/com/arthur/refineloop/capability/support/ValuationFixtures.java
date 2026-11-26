package com.arthur.refineloop.capability.support;

import org.springframework.jdbc.core.JdbcTemplate;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.function.IntFunction;

/**
 * 估值分位单元与最小闭环联调的测试夹具：往真实 SQLite 里造「股票 + 行业 + 三年每日指标」。
 *
 * <p><b>为什么用 JdbcTemplate 造数</b>：夹具的职责是「按上游契约原样写行」，走生产 upsert 通道反而要
 * 依赖注册表的列顺序；测试代码用 {@code JdbcTemplate} 直插，能把被测的读取路径当成黑盒。
 * 生产代码禁用 {@code JdbcTemplate} 的红线只约束 {@code src/main}（由 ArchUnit 的
 * {@code DoNotIncludeTests} 保证）。
 *
 * <p><b>批量插入</b>：三年序列动辄数百行，逐行 insert 会让每个用例多花十几秒（SQLite 每语句一次提交）；
 * 这里统一走 {@code batchUpdate}，把夹具耗时压到几十毫秒，让「多造几张全市场规模的池」成为可行选项。
 *
 * <p><b>口径</b>：{@code total_mv} 传万元（与 Tushare 一致），{@code pe_ttm/pb/ps_ttm} 传倍，
 * {@code dv_ttm} 传百分数；一律用 String 传入，避免 double 字面量在断言的最后一位上抖动。
 */
public final class ValuationFixtures {

    private static final DateTimeFormatter COMPACT = DateTimeFormatter.ofPattern("yyyyMMdd");

    private static final String INSERT_DAILY_BASIC =
            "INSERT INTO daily_basic (ts_code, trade_date, close, pe_ttm, pb, ps_ttm, dv_ttm, total_mv) "
                    + "VALUES (?, ?, ?, ?, ?, ?, ?, ?)";

    private ValuationFixtures() {
    }

    /** 一只在市股票（{@code list_status='L'}）。 */
    public static void stock(JdbcTemplate jdbc, String tsCode, String name) {
        jdbc.update("INSERT INTO stock_basic (ts_code, symbol, name, market, exchange, list_status, list_date) "
                        + "VALUES (?, ?, ?, '主板', ?, 'L', '20100101')",
                tsCode, symbolOf(tsCode), name, exchangeOf(tsCode));
    }

    /** 一条申万成分（{@code outDate} 为空表示仍在成分内）。 */
    public static void industryMember(JdbcTemplate jdbc, String tsCode, String l1Code,
                                      String inDate, String outDate) {
        jdbc.update("INSERT INTO sw_industry_member (l1_code, l1_name, ts_code, in_date, out_date) "
                        + "VALUES (?, ?, ?, ?, ?)",
                l1Code, l1Code + " 行业", tsCode, inDate, outDate);
    }

    /** 一行每日指标（null 允许，表示上游该字段缺失）。 */
    public static void dailyBasic(JdbcTemplate jdbc, String tsCode, String tradeDate,
                                  String peTtm, String pb, String psTtm, String dvTtm, String totalMvWan) {
        jdbc.update(INSERT_DAILY_BASIC, tsCode, tradeDate, decimal("10.00"), decimal(peTtm), decimal(pb),
                decimal(psTtm), decimal(dvTtm), decimal(totalMvWan));
    }

    /**
     * 以 {@code endTradeDate} 为最后一个交易日，倒数 {@code count} 个自然日（升序）的日期串。
     *
     * <p>用连续自然日而非真实交易日：单元只数「窗口内出现的日期数」，夹具需要的是可控的样本量，
     * 不需要仿真节假日。
     */
    public static List<String> datesEndingAt(String endTradeDate, int count) {
        LocalDate end = LocalDate.parse(endTradeDate, COMPACT);
        List<String> dates = new ArrayList<>(count);
        for (int i = count - 1; i >= 0; i--) {
            dates.add(end.minusDays(i).format(COMPACT));
        }
        return dates;
    }

    /** 按日期序列批量写一只标的的每日指标；{@code peForIndex} 决定第 i 个交易日的 pe_ttm。 */
    public static void series(JdbcTemplate jdbc, String tsCode, List<String> dates,
                              IntFunction<String> peForIndex, String pb, String psTtm,
                              String dvTtm, String totalMvWan) {
        List<Object[]> batch = new ArrayList<>(dates.size());
        for (int i = 0; i < dates.size(); i++) {
            batch.add(new Object[]{tsCode, dates.get(i), decimal("10.00"), decimal(peForIndex.apply(i)),
                    decimal(pb), decimal(psTtm), decimal(dvTtm), decimal(totalMvWan)});
        }
        if (!batch.isEmpty()) {
            jdbc.batchUpdate(INSERT_DAILY_BASIC, batch);
        }
    }

    /** 等差序列的第 i 个 pe：{@code start + i × step}（BigDecimal 精确计算，避免二进制抖动）。 */
    public static IntFunction<String> linearPe(String start, String step) {
        BigDecimal base = new BigDecimal(start);
        BigDecimal delta = new BigDecimal(step);
        return index -> base.add(delta.multiply(BigDecimal.valueOf(index))).toPlainString();
    }

    private static String symbolOf(String tsCode) {
        int dot = tsCode.indexOf('.');
        return dot < 0 ? tsCode : tsCode.substring(0, dot);
    }

    private static String exchangeOf(String tsCode) {
        return tsCode.endsWith(".SH") ? "SSE" : tsCode.endsWith(".BJ") ? "BSE" : "SZSE";
    }

    private static BigDecimal decimal(String value) {
        return value == null ? null : new BigDecimal(value);
    }
}
