package com.arthur.refineloop.capability.runtime.llm;

import com.arthur.refineloop.common.ApiException;
import com.arthur.refineloop.config.LlmProperties;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.ai.anthropic.AnthropicChatOptions;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.prompt.ChatOptions;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.web.client.RestClient;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowableOfType;

@DisplayName("LlmChatClientProvider：OpenAI / Anthropic 双协议构建")
class LlmChatClientProviderTest {

    @Test
    @DisplayName("OpenAI 协议可构建 ChatClient 与 OpenAiChatOptions")
    void buildsOpenAiClient() {
        LlmChatClientProvider provider = new LlmChatClientProvider(
                properties(LlmProtocol.OPENAI, true), RestClient.builder());

        ChatClient client = provider.clientFor("fast");
        ChatOptions options = provider.optionsFor("fast", 0.1, 128);

        assertThat(client).isNotNull();
        assertThat(options).isInstanceOf(OpenAiChatOptions.class);
        assertThat(((OpenAiChatOptions) options).getModel()).isEqualTo("test-model");
    }

    @Test
    @DisplayName("Anthropic 协议可构建 ChatClient 与 AnthropicChatOptions")
    void buildsAnthropicClient() {
        LlmChatClientProvider provider = new LlmChatClientProvider(
                properties(LlmProtocol.ANTHROPIC, true), RestClient.builder());

        ChatClient client = provider.clientFor("fast");
        ChatOptions options = provider.optionsFor("fast", 0.1, 128);

        assertThat(client).isNotNull();
        assertThat(options).isInstanceOf(AnthropicChatOptions.class);
        assertThat(((AnthropicChatOptions) options).getModel()).isEqualTo("test-model");
    }

    @Test
    @DisplayName("Key 为空抛 4104，且不阻断构建 provider")
    void blankKeyFailsOnUse() {
        LlmChatClientProvider provider = new LlmChatClientProvider(
                properties(LlmProtocol.OPENAI, false), RestClient.builder());

        ApiException error = catchThrowableOfType(() -> provider.clientFor("fast"), ApiException.class);
        assertThat(error).isNotNull();
        assertThat(error.getCode()).isEqualTo(4104);
    }

    @Test
    @DisplayName("未知 profile 抛 4104")
    void unknownProfileFails() {
        LlmChatClientProvider provider = new LlmChatClientProvider(
                properties(LlmProtocol.OPENAI, true), RestClient.builder());

        ApiException error = catchThrowableOfType(() -> provider.clientFor("strong"), ApiException.class);
        assertThat(error).isNotNull();
        assertThat(error.getCode()).isEqualTo(4104);
    }

    private LlmProperties properties(LlmProtocol protocol, boolean withKey) {
        LlmProperties properties = new LlmProperties();

        LlmProperties.Provider provider = new LlmProperties.Provider();
        provider.setProtocol(protocol);
        provider.setBaseUrl("http://127.0.0.1:1234");
        provider.setApiKey(withKey ? "test-key" : "");
        provider.setCompletionsPath(protocol == LlmProtocol.OPENAI
                ? "/v1/chat/completions" : "/v1/messages");
        properties.getProviders().put("local", provider);

        LlmProperties.Profile profile = new LlmProperties.Profile();
        profile.setProvider("local");
        profile.setModel("test-model");
        properties.getProfiles().put("fast", profile);
        return properties;
    }
}
