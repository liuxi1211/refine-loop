package com.arthur.refineloop.common.util;

import org.slf4j.Logger;

import java.util.ArrayList;
import java.util.List;

/**
 * 轻量计时器：给「一个阶段处理 N 张表/标的」的批处理加耗时观测，不引入任何依赖。
 *
 * <p>用法：
 * <pre>{@code
 * StopWatch watch = StopWatch.start("ingest.daily");
 * for (TableDef table : tables) {
 *     watch.lap(table.tableName());
 *     ...
 * }
 * log.info("{} 完成，总耗时 {}", watch.name(), watch.elapsedText());
 * watch.laps().forEach((label, millis) -> log.debug("  {} 耗时 {} ms", label, millis));
 * }</pre>
 *
 * <p>线程安全：<b>不是</b>线程安全的，一个任务一个实例（跨线程统计请改用 LongAdder）。
 */
public final class StopWatch {

    private final String name;
    private final long startedAtNanos;
    private final List<Lap> laps = new ArrayList<>();
    private long lastLapNanos;

    private StopWatch(String name) {
        this.name = name;
        this.startedAtNanos = System.nanoTime();
        this.lastLapNanos = startedAtNanos;
    }

    public static StopWatch start(String name) {
        return new StopWatch(name);
    }

    public String name() {
        return name;
    }

    /** 记录一段耗时（自上一个 lap 起算）。 */
    public StopWatch lap(String label) {
        long now = System.nanoTime();
        laps.add(new Lap(label, (now - lastLapNanos) / 1_000_000));
        lastLapNanos = now;
        return this;
    }

    public long elapsedMillis() {
        return (System.nanoTime() - startedAtNanos) / 1_000_000;
    }

    public String elapsedText() {
        return DateTimes.humanize(elapsedMillis());
    }

    public List<Lap> laps() {
        return List.copyOf(laps);
    }

    /** 打一条 debug 汇总（逐段耗时），交给调用方决定是否 info 汇总。 */
    public StopWatch logDebug(Logger log) {
        if (log.isDebugEnabled()) {
            log.debug("{} 耗时 {}（{} 段）", name, elapsedText(), laps.size());
            for (Lap lap : laps) {
                log.debug("  - {}: {}", lap.label(), DateTimes.humanize(lap.millis()));
            }
        }
        return this;
    }

    /** 单个计时段。 */
    public record Lap(String label, long millis) {
    }
}
