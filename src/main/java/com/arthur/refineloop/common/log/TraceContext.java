package com.arthur.refineloop.common.log;

import org.slf4j.MDC;

import java.util.Map;
import java.util.concurrent.ThreadLocalRandom;
import java.util.regex.Pattern;

/**
 * 日志上下文（MDC）统一入口：traceId / runId / span 等诊断字段只在这里读写。
 *
 * <p>为什么需要它：这个系统大量使用虚拟线程与线程池，日志会跨线程打散；没有 traceId 时
 * 「一次编排的 40 个表的日志」在文件里是完全无法聚合的。约定：
 * <ul>
 *   <li>HTTP 入口由 {@link TraceIdFilter} 生成/透传 {@code traceId}，并写回响应头
 *       {@code X-Trace-Id}；</li>
 *   <li>批处理/定时任务用 {@code try (var ignored = TraceContext.withRunId(runId))} 显式绑定；</li>
 *   <li>线程池任务用 {@link MdcTaskDecorator} 自动继承提交方的上下文；</li>
 *   <li>新线程/新任务里不要手工 {@code MDC.put}，统一走本类，避免键名漂移。</li>
 * </ul>
 *
 * <p>日志格式见 {@code logback-spring.xml}：{@code ... [traceId] - msg}。
 */
public final class TraceContext {

    /** MDC 键：链路号（一次 HTTP 请求或一次批处理运行）。 */
    public static final String TRACE_ID = "traceId";

    /** MDC 键：编排/流水线运行号（一次 run 内所有日志共享）。 */
    public static final String RUN_ID = "runId";

    /** MDC 键：阶段/单元标识（如 S1、unit_id、表名）。 */
    public static final String SPAN = "span";

    /** 传入 traceId 的白名单：只接受短小的字母数字与下划线/短横线，防止日志注入。 */
    private static final Pattern VALID_TRACE_ID = Pattern.compile("[A-Za-z0-9_-]{1,64}");

    private TraceContext() {
    }

    /** 当前 traceId；不在请求/任务上下文时返回 null。 */
    public static String currentTraceId() {
        return MDC.get(TRACE_ID);
    }

    /** 当前 traceId；缺失时返回占位符，便于日志拼接而不会打印 "null"。 */
    public static String currentTraceIdOrNone() {
        String traceId = MDC.get(TRACE_ID);
        return traceId == null || traceId.isBlank() ? "-" : traceId;
    }

    /** 生成新的 traceId（16 位十六进制，足够单机排障使用）。 */
    public static String newTraceId() {
        long value = ThreadLocalRandom.current().nextLong();
        // 保留 16 位，前导零补齐，长度固定便于肉眼比对
        return String.format("%016x", value);
    }

    public static boolean isValidTraceId(String candidate) {
        return candidate != null && VALID_TRACE_ID.matcher(candidate).matches();
    }

    /** 绑定 traceId；关闭时恢复原值（支持嵌套）。 */
    public static Scope withTraceId(String traceId) {
        return with(TRACE_ID, traceId);
    }

    /** 绑定 runId；关闭时恢复原值。 */
    public static Scope withRunId(String runId) {
        return with(RUN_ID, runId);
    }

    /** 绑定阶段标识（表名/单元 id）；关闭时恢复原值。 */
    public static Scope withSpan(String span) {
        return with(SPAN, span);
    }

    /**
     * 绑定任意 MDC 键；{@code value == null} 表示临时移除。关闭时恢复原值。
     */
    public static Scope with(String key, String value) {
        String previous = MDC.get(key);
        if (value == null) {
            MDC.remove(key);
        } else {
            MDC.put(key, value);
        }
        return new Scope(key, previous);
    }

    /** 捕获当前上下文（用于跨线程传递）。 */
    public static Map<String, String> capture() {
        Map<String, String> context = MDC.getCopyOfContextMap();
        return context == null ? Map.of() : Map.copyOf(context);
    }

    /** 在目标线程安装上下文（配合 {@link #capture()} 使用；结束务必调用 {@link #install(Map)} 传空）。 */
    public static void install(Map<String, String> context) {
        if (context == null || context.isEmpty()) {
            MDC.clear();
        } else {
            MDC.setContextMap(context);
        }
    }

    public static void clear() {
        MDC.clear();
    }

    /** 可关闭的作用域：关闭时精确还原前值（而不是无脑 remove）。 */
    public static final class Scope implements AutoCloseable {

        private final String key;
        private final String previous;

        private Scope(String key, String previous) {
            this.key = key;
            this.previous = previous;
        }

        @Override
        public void close() {
            if (previous == null) {
                MDC.remove(key);
            } else {
                MDC.put(key, previous);
            }
        }
    }
}
