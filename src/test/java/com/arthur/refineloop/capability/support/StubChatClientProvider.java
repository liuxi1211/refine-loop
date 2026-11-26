package com.arthur.refineloop.capability.support;

import com.arthur.refineloop.capability.runtime.llm.ChatClientProvider;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.metadata.ChatResponseMetadata;
import org.springframework.ai.chat.metadata.Usage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.ChatOptions;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 可编排输出的假 {@code ChatClientProvider}：把「模型返回什么」变成测试输入。
 *
 * <p><b>为什么不用 mock {@code LlmInvoker}</b>：T11 要验证的是<b>整条链路</b>——
 * 解析 → L3 五重校验 → 带错重试 → 降级 → {@code llm_usage} 记账。只在 {@code ChatClient} 这一层挡桩，
 * 校验链、解析器、用量记账、重试循环全部是真实代码，测出来的才是闭环；mock {@code LlmInvoker}
 * 会把校验链一起绕过，等于只测了存档器。
 *
 * <p><b>用法</b>：{@code new StubChatClientProvider().enqueue(第一次返回).enqueue(第二次返回)}
 * —— 每次 {@code chatResponse()} 依序取一条，耗尽即抛异常（让「调用次数超预期」立刻暴露，
 * 而不是静默返回空串把失败伪装成解析错误）。
 */
public class StubChatClientProvider implements ChatClientProvider {

    private static final String STUB_MODEL = "stub-model";

    private final Deque<String> contents = new ArrayDeque<>();
    private final List<String> systemPrompts = new ArrayList<>();
    private final ChatClient client;
    private final ChatOptions options = mock(ChatOptions.class);

    public StubChatClientProvider() {
        this.client = buildClient();
    }

    /** 追加一次模型返回（按调用顺序消费）。 */
    public StubChatClientProvider enqueue(String content) {
        contents.addLast(content == null ? "" : content);
        return this;
    }

    /** 尚未消费的返回条数（断言「是否发生了重试」用）。 */
    public int remaining() {
        return contents.size();
    }

    /** 已发生的调用次数（= 系统提示词条数）。 */
    public int callCount() {
        return systemPrompts.size();
    }

    /** 全部系统提示词（断言「错误回喂是否进了 prompt」用）。 */
    public List<String> systemPrompts() {
        synchronized (systemPrompts) {
            return List.copyOf(systemPrompts);
        }
    }

    @Override
    public ChatClient clientFor(String profile) {
        return client;
    }

    @Override
    public ChatOptions optionsFor(String profile, Double temperature, Integer maxTokens) {
        return options;
    }

    private ChatClient buildClient() {
        ChatClient stub = mock(ChatClient.class);
        ChatClient.ChatClientRequestSpec requestSpec = mock(ChatClient.ChatClientRequestSpec.class);
        ChatClient.CallResponseSpec callSpec = mock(ChatClient.CallResponseSpec.class);
        when(stub.prompt()).thenReturn(requestSpec);
        when(requestSpec.system(anyString())).thenAnswer(invocation -> {
            synchronized (systemPrompts) {
                systemPrompts.add(invocation.getArgument(0));
            }
            return requestSpec;
        });
        when(requestSpec.user(anyString())).thenReturn(requestSpec);
        when(requestSpec.options(any(ChatOptions.class))).thenReturn(requestSpec);
        when(requestSpec.call()).thenReturn(callSpec);
        when(callSpec.chatResponse()).thenAnswer(invocation -> response(nextContent()));
        return stub;
    }

    private String nextContent() {
        String content;
        synchronized (contents) {
            content = contents.pollFirst();
        }
        if (content == null) {
            throw new IllegalStateException("StubChatClientProvider 的返回已耗尽：实际调用次数超过预期");
        }
        return content;
    }

    private static ChatResponse response(String text) {
        ChatResponse response = mock(ChatResponse.class);
        Generation generation = mock(Generation.class);
        AssistantMessage message = mock(AssistantMessage.class);
        ChatResponseMetadata metadata = mock(ChatResponseMetadata.class);
        Usage usage = mock(Usage.class);
        when(response.getResult()).thenReturn(generation);
        when(generation.getOutput()).thenReturn(message);
        when(message.getText()).thenReturn(text);
        when(response.getMetadata()).thenReturn(metadata);
        when(metadata.getModel()).thenReturn(STUB_MODEL);
        when(metadata.getUsage()).thenReturn(usage);
        when(usage.getPromptTokens()).thenReturn(320);
        when(usage.getCompletionTokens()).thenReturn(180);
        when(usage.getTotalTokens()).thenReturn(500);
        return response;
    }
}
