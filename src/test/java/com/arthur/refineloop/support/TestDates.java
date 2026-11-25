package com.arthur.refineloop.support;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;

/** 测试数据/断言常用的小工具。 */
public final class TestDates {

    private static final DateTimeFormatter BASIC = DateTimeFormatter.ofPattern("yyyyMMdd");

    private TestDates() {
    }

    public static String fmt(LocalDate date) {
        return date.format(BASIC);
    }

    public static String today() {
        return fmt(LocalDate.now());
    }

    /** 2026-01-02 是周五（pledge_stat 用例）。 */
    public static final String FRIDAY_20260102 = "20260102";
}
