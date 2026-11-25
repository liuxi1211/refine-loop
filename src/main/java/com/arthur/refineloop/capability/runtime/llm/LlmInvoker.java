package com.arthur.refineloop.capability.runtime.llm;

import com.arthur.refineloop.common.log.TraceContext;
import com.arthur.refineloop.config.LlmProperties;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.metadata.Usage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.ChatOptions;

import java.math.BigDecimal;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * LLM 调用基座：业务代码面向 ChatClient 的唯一出口。
 *
 * <p>职责：
 * <ul>
 *   <li>按 profile 取客户端与协议化 options；</li>
 *   <li>单次调用硬超时（默认 120s，虚拟线程 + Future）；超时不重试；</li>
 *   <li>429 独立退避，瞬时错误指数退避 + 抖动，4xx 不重试；</li>
 *   <li>成功调用解析 usage，写 {@code llm_usage} 并返回 token/费用。</li>
 * </ul>
 */
@Slf4j
public class LlmInvoker {

    private static final int MAX_BACKOFF_MILLIS = 60_000;

    private final ChatClientProvider chatClientProvider;
    private final LlmUsageStore usageStore;
    private final LlmProperties properties;
    private final ExecutorService callExecutor;

    public LlmInvoker(ChatClientProvider chatClientProvider,
                      LlmUsageStore usageStore,
                      LlmProperties properties,
                      ExecutorService callExecutor) {
        this.chatClientProvider = chatClientProvider;
        this.usageStore = usageStore;
        this.properties = properties;
        this.callExecutor = callExecutor;
    }

    /**
     * 执行一次 LLM 调用（含重试与记账）。
     */
    public LlmCallResult invoke(LlmCallSpec spec) {
        LlmProperties.Profile profile = properties.profile(spec.profile());
        if (profile == null) {
            throw LlmException.notConfigured("未配置 LLM profile：" + spec.profile());
        }
        ChatClient client = chatClientProvider.clientFor(spec.profile());
        ChatOptions options = chatClientProvider.optionsFor(
                spec.profile(), spec.temperature(), spec.maxTokens());
        int timeoutSeconds = profile.getTimeoutSeconds() == null ? 120 : profile.getTimeoutSeconds();
        int maxAttempts = Math.max(1, properties.getRetry().getMaxAttempts());
        long startNanos = System.nanoTime();

        LlmException lastFailure = null;
        for (int attempt = 1; attempt <= maxAttempts; attempt++) {
            try {
                ChatResponse response = callOnce(client, spec, options, timeoutSeconds);
                return toResult(spec, profile, response, attempt, startNanos);
            } catch (LlmException e) {
                lastFailure = e;
                if (!retryable(e) || attempt >= maxAttempts) {
                    throw e;
                }
                sleep(backoffMillis(e, attempt), spec.profile(), attempt);
            } catch (RuntimeException e) {
                LlmException mapped = mapFailure(spec, e);
                lastFailure = mapped;
                if (!retryable(e) || attempt >= maxAttempts) {
                    throw mapped;
                }
                sleep(backoffMillis(mapped, attempt), spec.profile(), attempt);
            }
        }
        throw lastFailure == null
                ? LlmException.callFailed("LLM 调用失败", null)
                : lastFailure;
    }

    private ChatResponse callOnce(ChatClient client, LlmCallSpec spec,
                                  ChatOptions options, int timeoutSeconds) {
        Future<ChatResponse> future = callExecutor.submit(() -> client.prompt()
                .system(spec.systemPrompt())
                .user(spec.userPrompt())
                .options(options)
                .call()
                .chatResponse());
        try {
            return future.get(timeoutSeconds, TimeUnit.SECONDS);
        } catch (TimeoutException e) {
            future.cancel(true);
            throw LlmException.timeout(
                    "LLM 调用超时（>" + timeoutSeconds + "s，profile=" + spec.profile() + "）", e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw LlmException.callFailed("LLM 调用被中断", e);
        } catch (ExecutionException e) {
            throw asRuntime(e.getCause());
        }
    }

    private LlmCallResult toResult(LlmCallSpec spec, LlmProperties.Profile profile,
                                   ChatResponse response, int attempts, long startNanos) {
        if (response == null || response.getResult() == null
                || response.getResult().getOutput() == null
                || response.getResult().getOutput().getText() == null
                || response.getResult().getOutput().getText().isBlank()) {
            throw LlmException.responseEmpty("LLM 返回为空（profile=" + spec.profile() + "）");
        }
        String content = response.getResult().getOutput().getText();
        String model = response.getMetadata() == null ? null : response.getMetadata().getModel();
        if (model == null || model.isBlank()) {
            model = profile.getModel();
        }
        Usage usage = response.getMetadata() == null ? null : response.getMetadata().getUsage();
        int promptTokens = tokenCount(usage == null ? null : usage.getPromptTokens());
        int completionTokens = tokenCount(usage == null ? null : usage.getCompletionTokens());
        int totalTokens = tokenCount(usage == null ? null : usage.getTotalTokens());
        if (totalTokens == 0) {
            totalTokens = promptTokens + completionTokens;
        }
        BigDecimal cost = LlmUsageStore.estimateCost(promptTokens, completionTokens,
                profile.getInputPricePer1m(), profile.getOutputPricePer1m());
        String runId = resolveRunId(spec);
        long usageId = usageStore.record(runId, spec.unitId(), spec.profile(), model,
                promptTokens, completionTokens, totalTokens, cost);
        long durationMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startNanos);
        log.info("[llm] 调用成功 profile={} unit={} runId={} model={} tokens={}/{}/{} attempts={} "
                        + "durationMs={} cost={} usageId={}",
                spec.profile(), spec.unitId(), runId, model, promptTokens, completionTokens,
                totalTokens, attempts, durationMs, cost, usageId);
        return new LlmCallResult(content, model, promptTokens, completionTokens,
                totalTokens, cost, attempts, durationMs);
    }

    private LlmException mapFailure(LlmCallSpec spec, RuntimeException cause) {
        if (isRateLimit(cause)) {
            return LlmException.rateLimited(
                    "LLM 调用被限流（profile=" + spec.profile() + "）：" + safeMessage(cause), cause);
        }
        return LlmException.callFailed(
                "LLM 调用失败（profile=" + spec.profile() + "）：" + safeMessage(cause), cause);
    }

    /** 429 可重试；超时不可重试；瞬时错误可重试；4xx 不重试。 */
    private boolean retryable(Throwable throwable) {
        if (throwable instanceof LlmException llmException) {
            if (llmException.isTimeout()) {
                return false;
            }
            if (llmException.isRateLimited()) {
                return true;
            }
            return isTransient(llmException.getCause());
        }
        return isTransient(throwable);
    }

    private boolean isRateLimit(Throwable throwable) {
        for (Throwable current = throwable; current != null; current = current.getCause()) {
            String className = current.getClass().getSimpleName().toLowerCase();
            String message = current.getMessage() == null ? "" : current.getMessage().toLowerCase();
            if (className.contains("429")
                    || message.contains("429")
                    || message.contains("rate limit")
                    || message.contains("too many requests")) {
                return true;
            }
        }
        return false;
    }

    private boolean isTransient(Throwable throwable) {
        if (throwable == null) {
            return false;
        }
        if (isRateLimit(throwable)) {
            return true;
        }
        for (Throwable current = throwable; current != null; current = current.getCause()) {
            String className = current.getClass().getSimpleName().toLowerCase();
            String message = current.getMessage() == null ? "" : current.getMessage().toLowerCase();
            if (className.contains("nontransient")) {
                return false;
            }
            if (className.contains("transient")
                    || className.contains("timeout")
                    || className.contains("resourceaccess")
                    || className.contains("webclientrequest")
                    || className.contains("ioexception")
                    || className.contains("connectexception")) {
                return true;
            }
            if (message.contains("503") || message.contains("502") || message.contains("504")
                    || message.contains("timeout") || message.contains("timed out")
                    || message.contains("connection reset") || message.contains("connection refused")) {
                return true;
            }
        }
        return false;
    }

    private long backoffMillis(LlmException exception, int attempt) {
        LlmProperties.Retry retry = properties.getRetry();
        long base = exception.isRateLimited()
                ? retry.getRateLimitBackoffMillis() * attempt
                : (long) (retry.getInitialBackoffMillis() * Math.pow(retry.getMultiplier(), attempt - 1));
        long jitter = exception.isRateLimited() ? 0 : ThreadLocalRandom.current().nextLong(0, Math.max(1, base / 4));
        return Math.min(MAX_BACKOFF_MILLIS, Math.max(1, base + jitter));
    }

    private void sleep(long millis, String profile, int attempt) {
        try {
            log.warn("[llm] 第 {} 次调用失败，{}ms 后重试 profile={}", attempt, millis, profile);
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw LlmException.callFailed("LLM 重试等待被中断", e);
        }
    }

    private RuntimeException asRuntime(Throwable cause) {
        if (cause instanceof RuntimeException runtimeException) {
            return runtimeException;
        }
        return new IllegalStateException(cause == null ? "LLM 调用失败" : cause.getMessage(), cause);
    }

    private String resolveRunId(LlmCallSpec spec) {
        if (spec.runId() != null && !spec.runId().isBlank()) {
            return spec.runId();
        }
        String runId = TraceContext.currentRunId();
        return runId == null || runId.isBlank() ? null : runId;
    }

    private int tokenCount(Integer value) {
        return value == null ? 0 : Math.max(0, value);
    }

    private String safeMessage(Throwable throwable) {
        String message = throwable.getMessage();
        return message == null || message.isBlank() ? throwable.getClass().getSimpleName() : message;
    }
}
