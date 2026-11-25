package com.arthur.refineloop.capability.runtime.llm;

import com.arthur.refineloop.common.ApiException;
import com.arthur.refineloop.config.LlmProperties;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.metadata.Usage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.metadata.ChatResponseMetadata;
import org.springframework.ai.chat.prompt.ChatOptions;

import java.math.BigDecimal;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowableOfType;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@DisplayName("LlmInvoker：重试、429、超时、记账")
class LlmInvokerTest {

    private ExecutorService executor;
    private LlmProperties properties;
    private LlmUsageStore usageStore;
    private ChatClientProvider chatClientProvider;
    private LlmInvoker invoker;

    @BeforeEach
    void setUp() {
        executor = Executors.newVirtualThreadPerTaskExecutor();
        properties = new LlmProperties();
        LlmProperties.Profile profile = new LlmProperties.Profile();
        profile.setProvider("test");
        profile.setModel("test-model");
        profile.setTimeoutSeconds(120);
        profile.setInputPricePer1m(new BigDecimal("1.0"));
        profile.setOutputPricePer1m(new BigDecimal("2.0"));
        properties.getProfiles().put("fast", profile);
        properties.getRetry().setMaxAttempts(3);
        properties.getRetry().setInitialBackoffMillis(1);
        properties.getRetry().setMultiplier(2.0);
        properties.getRetry().setRateLimitBackoffMillis(1);

        usageStore = mock(LlmUsageStore.class);
        when(usageStore.record(any(), any(), any(), any(), anyInt(), anyInt(), anyInt(), any()))
                .thenReturn(42L);
        chatClientProvider = mock(ChatClientProvider.class);
        invoker = new LlmInvoker(chatClientProvider, usageStore, properties, executor);
    }

    @AfterEach
    void tearDown() {
        executor.shutdownNow();
    }

    @Test
    @DisplayName("成功调用：解析 token、估算费用并记账")
    void successRecordsUsage() {
        ChatClient client = mockClient();
        ChatClient.CallResponseSpec callSpec = attachClient(client, response("hello", "test-model", 10, 20, 30));
        when(chatClientProvider.clientFor("fast")).thenReturn(client);
        when(chatClientProvider.optionsFor(eq("fast"), any(), any())).thenReturn(mock(ChatOptions.class));

        LlmCallResult result = invoker.invoke(spec());

        assertThat(result.content()).isEqualTo("hello");
        assertThat(result.attempts()).isEqualTo(1);
        assertThat(result.promptTokens()).isEqualTo(10);
        assertThat(result.completionTokens()).isEqualTo(20);
        assertThat(result.totalTokens()).isEqualTo(30);
        assertThat(result.cost()).isEqualByComparingTo("0.00005");
        verify(usageStore, times(1)).record(
                eq("run-1"), eq("unit-1"), eq("fast"), eq("test-model"), eq(10), eq(20), eq(30), any());
        verify(callSpec, times(1)).chatResponse();
    }

    @Test
    @DisplayName("瞬时 503：重试后成功")
    void transientFailureRetriesThenSucceeds() {
        ChatClient client = mockClient();
        ChatClient.CallResponseSpec callSpec = attachClient(client, response("hello", "test-model", 1, 1, 2));
        when(callSpec.chatResponse())
                .thenThrow(new RuntimeException("503 Service Unavailable"))
                .thenReturn(response("hello", "test-model", 1, 1, 2));
        when(chatClientProvider.clientFor("fast")).thenReturn(client);
        when(chatClientProvider.optionsFor(eq("fast"), any(), any())).thenReturn(mock(ChatOptions.class));

        LlmCallResult result = invoker.invoke(spec());

        assertThat(result.attempts()).isEqualTo(2);
        verify(callSpec, times(2)).chatResponse();
    }

    @Test
    @DisplayName("429：按限流退避重试，最终抛 4103")
    void rateLimitRetriesThenFails() {
        properties.getRetry().setMaxAttempts(2);
        ChatClient client = mockClient();
        ChatClient.CallResponseSpec callSpec = attachClient(client, response("unused", "m", 0, 0, 0));
        when(callSpec.chatResponse()).thenThrow(new RuntimeException("429 Too Many Requests"));
        when(chatClientProvider.clientFor("fast")).thenReturn(client);
        when(chatClientProvider.optionsFor(eq("fast"), any(), any())).thenReturn(mock(ChatOptions.class));

        ApiException error = catchThrowableOfType(() -> invoker.invoke(spec()), ApiException.class);

        assertThat(error).isNotNull();
        assertThat(error.getCode()).isEqualTo(4103);
        verify(callSpec, times(2)).chatResponse();
    }

    @Test
    @DisplayName("超时：硬超时抛 4102，且不记账")
    void timeoutThrowsAndDoesNotRecord() {
        properties.profile("fast").setTimeoutSeconds(0);
        ChatClient client = mockClient();
        ChatClient.CallResponseSpec callSpec = attachClient(client, response("late", "m", 0, 0, 0));
        when(callSpec.chatResponse()).thenAnswer(invocation -> {
            Thread.sleep(500);
            return response("late", "m", 0, 0, 0);
        });
        when(chatClientProvider.clientFor("fast")).thenReturn(client);
        when(chatClientProvider.optionsFor(eq("fast"), any(), any())).thenReturn(mock(ChatOptions.class));

        ApiException error = catchThrowableOfType(() -> invoker.invoke(spec()), ApiException.class);

        assertThat(error).isNotNull();
        assertThat(error.getCode()).isEqualTo(4102);
        verify(usageStore, times(0)).record(any(), any(), any(), any(), anyInt(), anyInt(), anyInt(), any());
    }

    @Test
    @DisplayName("空响应抛 4106")
    void emptyResponseRejected() {
        ChatClient client = mockClient();
        attachClient(client, response("", "m", 1, 1, 2));
        when(chatClientProvider.clientFor("fast")).thenReturn(client);
        when(chatClientProvider.optionsFor(eq("fast"), any(), any())).thenReturn(mock(ChatOptions.class));

        ApiException error = catchThrowableOfType(() -> invoker.invoke(spec()), ApiException.class);

        assertThat(error).isNotNull();
        assertThat(error.getCode()).isEqualTo(4106);
    }

    private LlmCallSpec spec() {
        return new LlmCallSpec("fast", "unit-1", "run-1", "system", "user", null, null);
    }

    private ChatClient mockClient() {
        return mock(ChatClient.class);
    }

    private ChatClient.CallResponseSpec attachClient(ChatClient client, ChatResponse response) {
        ChatClient.ChatClientRequestSpec requestSpec = mock(ChatClient.ChatClientRequestSpec.class);
        ChatClient.CallResponseSpec callSpec = mock(ChatClient.CallResponseSpec.class);
        when(client.prompt()).thenReturn(requestSpec);
        when(requestSpec.system(any(String.class))).thenReturn(requestSpec);
        when(requestSpec.user(any(String.class))).thenReturn(requestSpec);
        when(requestSpec.options(any(ChatOptions.class))).thenReturn(requestSpec);
        when(requestSpec.call()).thenReturn(callSpec);
        when(callSpec.chatResponse()).thenReturn(response);
        return callSpec;
    }

    private ChatResponse response(String text, String model, int promptTokens,
                                  int completionTokens, int totalTokens) {
        ChatResponse response = mock(ChatResponse.class);
        Generation generation = mock(Generation.class);
        AssistantMessage message = mock(AssistantMessage.class);
        ChatResponseMetadata metadata = mock(ChatResponseMetadata.class);
        Usage usage = mock(Usage.class);
        when(response.getResult()).thenReturn(generation);
        when(generation.getOutput()).thenReturn(message);
        when(message.getText()).thenReturn(text);
        when(response.getMetadata()).thenReturn(metadata);
        when(metadata.getModel()).thenReturn(model);
        when(metadata.getUsage()).thenReturn(usage);
        when(usage.getPromptTokens()).thenReturn(promptTokens);
        when(usage.getCompletionTokens()).thenReturn(completionTokens);
        when(usage.getTotalTokens()).thenReturn(totalTokens);
        return response;
    }
}
