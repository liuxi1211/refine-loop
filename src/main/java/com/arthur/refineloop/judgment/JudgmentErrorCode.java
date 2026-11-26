package com.arthur.refineloop.judgment;

import com.arthur.refineloop.common.error.ErrorCode;

/**
 * 判断域错误码（6001-6099）。
 *
 * <p>分段依据《开发规范 04-接口契约规范》§3.3：判断与验证域 6001+，只增不改。
 * 触发条件逐条写在常量上——这些码会出现在日志告警与（M1 后）接口返回里，
 * 只写「判断不合法」会让排查时无法区分「调用方传入坏数据」与「存档器自身的缺陷」。
 */
public enum JudgmentErrorCode implements ErrorCode {

    /** 判断块或身份信息不合法：缺字段、枚举越界、编号/方法版本格式错、单元引用不自洽。 */
    PAYLOAD_INVALID(6001, "判断载荷不合法"),

    /** 试图修改已冻结判断的内容（本系统没有该入口，出现在这里说明有人绕过了 Mapper 白名单）。 */
    FROZEN_IMMUTABLE(6002, "判断已冻结，内容不可修改"),

    /** 判断已被归因/待归因引用，禁止自动重跑覆盖，需人工标注失效（M3 实化检查）。 */
    REFERENCED_BY_ATTRIBUTION(6003, "判断已被归因引用，禁止自动重跑"),

    /** 同日序号超过 4 位上限（9999）：正常业务不可能发生，属数据异常。 */
    SEQUENCE_EXHAUSTED(6004, "判断编号序号已耗尽"),

    /** 并发插入同一编号，重试次数耗尽（并发写同一 trade_date+pipeline 的强信号）。 */
    CONCURRENT_CONFLICT(6005, "判断编号并发冲突，重试耗尽");

    private final int code;
    private final String message;

    JudgmentErrorCode(int code, String message) {
        this.code = code;
        this.message = message;
    }

    @Override
    public int code() {
        return code;
    }

    @Override
    public String message() {
        return message;
    }
}
