package com.arthur.refineloop.tushare.ingest.service;

import com.arthur.refineloop.common.dict.DictEnum;
import com.arthur.refineloop.common.dict.DictTypes;

import java.util.Arrays;
import java.util.List;
import java.util.Optional;

/**
 * 数据接入作业状态（{@code ingest_log.status} 的唯一取值集合）。
 *
 * <p>状态机：PENDING → RUNNING → SUCCESS / FAILED / SKIPPED。实现 {@link DictEnum} 后：
 * <ul>
 *   <li>前端 {@code /api/common/dicts} 直接拿到「等待/运行中/成功/失败/跳过」与语义色，
 *       不再各自维护一份映射；</li>
 *   <li>控制器入参校验、错误提示的允许值清单都从本枚举派生，不再手写 {@code Set.of(...)}。</li>
 * </ul>
 */
public enum IngestJobStatus implements DictEnum {

    PENDING("PENDING", "等待", "idle"),
    RUNNING("RUNNING", "运行中", "run"),
    SUCCESS("SUCCESS", "成功", "ok"),
    FAILED("FAILED", "失败", "err"),
    SKIPPED("SKIPPED", "跳过", "idle");

    private final String code;
    private final String label;
    private final String color;

    IngestJobStatus(String code, String label, String color) {
        this.code = code;
        this.label = label;
        this.color = color;
    }

    @Override
    public String dictType() {
        return DictTypes.JOB_STATUS;
    }

    @Override
    public String code() {
        return code;
    }

    @Override
    public String label() {
        return label;
    }

    @Override
    public String color() {
        return color;
    }

    @Override
    public int sort() {
        return ordinal() * 10;
    }

    /** 终态（不会再变化）：SUCCESS / FAILED / SKIPPED。 */
    public boolean isTerminal() {
        return this == SUCCESS || this == FAILED || this == SKIPPED;
    }

    public static List<String> codes() {
        return Arrays.stream(values()).map(IngestJobStatus::code).toList();
    }

    /** 按 code 解析（容忍大小写与空白）；未知返回 empty。 */
    public static Optional<IngestJobStatus> from(String code) {
        if (code == null) {
            return Optional.empty();
        }
        String normalized = code.trim().toUpperCase(java.util.Locale.ROOT);
        return Arrays.stream(values()).filter(status -> status.code.equals(normalized)).findFirst();
    }
}
