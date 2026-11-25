package com.arthur.refineloop.config;

import com.arthur.refineloop.capability.runtime.llm.ReportGenerator;
import com.arthur.refineloop.common.util.DateTimes;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.Map;

/**
 * LLM 连通性冒烟：仅当 {@code llm.smoke-on-startup=true} 时真实调用一次 fast 档。
 *
 * <p>用于验证用户自带的火山方舟 / DeepSeek / 自建 Anthropic Key 是否可用；失败只记 error，
 * 不阻断应用启动（避免 Key 临时失效导致服务起不来）。
 */
@Slf4j
@Component
@Order(20)
public class LlmSmokeRunner implements ApplicationRunner {

    private final LlmProperties llmProperties;
    private final ReportGenerator reportGenerator;
    private final java.time.Clock clock;

    public LlmSmokeRunner(LlmProperties llmProperties,
                          ReportGenerator reportGenerator,
                          java.time.Clock clock) {
        this.llmProperties = llmProperties;
        this.reportGenerator = reportGenerator;
        this.clock = clock;
    }

    @Override
    public void run(ApplicationArguments args) {
        if (!llmProperties.isSmokeOnStartup()) {
            return;
        }
        Map<String, Object> variables = new HashMap<>();
        variables.put("tradeDate", DateTimes.formatCompact(DateTimes.today(clock)));
        variables.put("facts", "（冒烟测试：无真实事实数据，只需确认模型可连通。）");
        try {
            ReportGenerator.ReportOutput output = reportGenerator.generate(
                    new ReportGenerator.ReportRequest(
                            "daily_summary", "v1", "daily_summary", null, "fast", variables));
            log.info("[llm] 冒烟成功 profile=fast model={} tokens={}/{}/{} cost={} text={}",
                    output.call().model(),
                    output.call().promptTokens(),
                    output.call().completionTokens(),
                    output.call().totalTokens(),
                    output.call().cost(),
                    output.text());
        } catch (RuntimeException e) {
            log.error("[llm] 冒烟失败（不阻断启动）：{}", e.getMessage(), e);
        }
    }
}
