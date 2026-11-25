package com.arthur.refineloop.config;

import com.arthur.refineloop.capability.runtime.llm.ChatClientProvider;
import com.arthur.refineloop.capability.runtime.llm.LlmChatClientProvider;
import com.arthur.refineloop.capability.runtime.llm.LlmInvoker;
import com.arthur.refineloop.capability.runtime.llm.LlmUsageStore;
import com.arthur.refineloop.capability.runtime.llm.PromptTemplateLoader;
import com.arthur.refineloop.capability.runtime.llm.ReportGenerator;
import com.arthur.refineloop.data.mapper.LlmUsageMapper;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.client.RestClient;

import java.time.Clock;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * LLM 调用基座装配：虚拟线程执行器、双协议客户端、用量记账、提示词加载、报告生成。
 *
 * <p>不使用 Spring AI starter auto-config：多 profile / 多协议需要按配置手动构建 ChatModel。
 */
@Configuration
public class LlmConfig {

    @Bean(destroyMethod = "shutdown")
    public ExecutorService llmCallExecutor() {
        return Executors.newVirtualThreadPerTaskExecutor();
    }

    @Bean
    public ChatClientProvider chatClientProvider(LlmProperties llmProperties,
                                                 RestClient.Builder restClientBuilder) {
        return new LlmChatClientProvider(llmProperties, restClientBuilder);
    }

    @Bean
    public LlmUsageStore llmUsageStore(LlmUsageMapper llmUsageMapper, Clock clock) {
        return new LlmUsageStore(llmUsageMapper, clock);
    }

    @Bean
    public LlmInvoker llmInvoker(ChatClientProvider chatClientProvider,
                                 LlmUsageStore llmUsageStore,
                                 LlmProperties llmProperties,
                                 ExecutorService llmCallExecutor) {
        return new LlmInvoker(chatClientProvider, llmUsageStore, llmProperties, llmCallExecutor);
    }

    @Bean
    public PromptTemplateLoader promptTemplateLoader() {
        return new PromptTemplateLoader();
    }

    @Bean
    public ReportGenerator reportGenerator(PromptTemplateLoader promptTemplateLoader,
                                           LlmInvoker llmInvoker) {
        return new ReportGenerator(promptTemplateLoader, llmInvoker);
    }
}
