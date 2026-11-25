package com.arthur.refineloop.capability.runtime.llm;

import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.prompt.ChatOptions;

/**
 * 按 profile 提供 {@link ChatClient} 与协议化 {@link ChatOptions}。
 *
 * <p>抽成接口的目的：单测可注入 mock，不需要真实网络；协议差异（OpenAI/Anthropic）封装在实现里。
 */
public interface ChatClientProvider {

    /** 取（或惰性构建）profile 对应的 ChatClient。 */
    ChatClient clientFor(String profile);

    /** 按 profile 的协议构造调用选项；temperature/maxTokens 为 null 时使用档位默认值。 */
    ChatOptions optionsFor(String profile, Double temperature, Integer maxTokens);
}
