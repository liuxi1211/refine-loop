package com.arthur.refineloop.common.log;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

/** 日志上下文：作用域嵌套还原、跨线程传递、traceId 白名单。 */
class TraceContextTest {

    @AfterEach
    void tearDown() {
        TraceContext.clear();
    }

    @Test
    void 作用域可嵌套且精确还原() {
        assertThat(TraceContext.currentTraceId()).isNull();

        try (TraceContext.Scope outer = TraceContext.withTraceId("outer")) {
            assertThat(TraceContext.currentTraceId()).isEqualTo("outer");
            try (TraceContext.Scope inner = TraceContext.withTraceId("inner")) {
                assertThat(TraceContext.currentTraceId()).isEqualTo("inner");
            }
            assertThat(TraceContext.currentTraceId()).isEqualTo("outer");

            try (TraceContext.Scope runScope = TraceContext.withRunId("R20261124")) {
                try (TraceContext.Scope spanScope = TraceContext.withSpan("ingest.daily")) {
                    Map<String, String> context = TraceContext.capture();
                    assertThat(context)
                            .containsEntry(TraceContext.TRACE_ID, "outer")
                            .containsEntry(TraceContext.RUN_ID, "R20261124")
                            .containsEntry(TraceContext.SPAN, "ingest.daily");
                }
                assertThat(TraceContext.capture()).doesNotContainKey(TraceContext.SPAN);
            }
            assertThat(TraceContext.capture()).doesNotContainKey(TraceContext.RUN_ID);
        }

        assertThat(TraceContext.currentTraceId()).isNull();
    }

    @Test
    void 上下文可跨线程传递() throws InterruptedException {
        AtomicReference<String> seen = new AtomicReference<>();
        Map<String, String> captured;
        try (TraceContext.Scope ignored = TraceContext.withTraceId("t-cross")) {
            captured = TraceContext.capture();
        }

        Thread worker = new Thread(() -> {
            TraceContext.install(captured);
            seen.set(TraceContext.currentTraceId());
            TraceContext.clear();
        });
        worker.start();
        worker.join();

        assertThat(seen.get()).isEqualTo("t-cross");
        assertThat(TraceContext.currentTraceId()).isNull();
    }

    @Test
    void traceId_生成与白名单校验() {
        String generated = TraceContext.newTraceId();
        assertThat(generated).hasSize(16).matches("[0-9a-f]{16}");
        assertThat(TraceContext.isValidTraceId(generated)).isTrue();
        assertThat(TraceContext.isValidTraceId("abc-DEF_123")).isTrue();
        assertThat(TraceContext.isValidTraceId("")).isFalse();
        assertThat(TraceContext.isValidTraceId(null)).isFalse();
        assertThat(TraceContext.isValidTraceId("with space")).isFalse();
        assertThat(TraceContext.isValidTraceId("x".repeat(65))).isFalse();
        // 日志注入防护：换行必须被拒绝
        assertThat(TraceContext.isValidTraceId("abc\nforged")).isFalse();
    }

    @Test
    void 缺失traceId时用占位符而不是null() {
        assertThat(TraceContext.currentTraceIdOrNone()).isEqualTo("-");
        try (TraceContext.Scope ignored = TraceContext.withTraceId("abc")) {
            assertThat(TraceContext.currentTraceIdOrNone()).isEqualTo("abc");
        }
    }
}
