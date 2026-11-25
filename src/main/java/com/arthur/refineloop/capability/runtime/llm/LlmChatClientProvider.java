package com.arthur.refineloop.capability.runtime.llm;

import com.arthur.refineloop.config.LlmProperties;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.anthropic.AnthropicChatModel;
import org.springframework.ai.anthropic.AnthropicChatOptions;
import org.springframework.ai.anthropic.api.AnthropicApi;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.prompt.ChatOptions;
import org.springframework.ai.openai.OpenAiChatModel;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.ai.openai.api.OpenAiApi;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

import java.net.http.HttpClient;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 双协议 ChatClient 工厂：按 profile 惰性构建并缓存。
 *
 * <p>支持 OpenAI 兼容协议（方舟/DeepSeek/DashScope 兼容模式/自建）与 Anthropic Messages 协议
 * （Claude 官方/one-api/new-api/LiteLLM 自建）。未配置 Key 时不报错，只在真正调用时抛 4104。
 */
@Slf4j
public class LlmChatClientProvider implements ChatClientProvider {

    private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(10);
    private static final String OPENAI_DEFAULT_PATH = "/v1/chat/completions";
    private static final String ANTHROPIC_DEFAULT_PATH = "/v1/messages";

    private final LlmProperties properties;
    private final RestClient.Builder restClientBuilder;
    private final Map<String, ChatClient> clients = new ConcurrentHashMap<>();

    public LlmChatClientProvider(LlmProperties properties, RestClient.Builder restClientBuilder) {
        this.properties = properties;
        this.restClientBuilder = restClientBuilder;
    }

    @Override
    public ChatClient clientFor(String profile) {
        return clients.computeIfAbsent(profile, this::buildClient);
    }

    @Override
    public ChatOptions optionsFor(String profile, Double temperature, Integer maxTokens) {
        LlmProperties.Profile profileConfig = requireProfile(profile);
        double effectiveTemperature = temperature == null
                ? defaultValue(profileConfig.getTemperature(), 0.3)
                : temperature;
        int effectiveMaxTokens = maxTokens == null
                ? defaultInt(profileConfig.getMaxTokens(), 2048)
                : maxTokens;
        LlmProperties.Provider provider = requireProvider(profile, profileConfig);
        return switch (protocolOf(provider)) {
            case OPENAI -> OpenAiChatOptions.builder()
                    .model(profileConfig.getModel())
                    .temperature(effectiveTemperature)
                    .maxTokens(effectiveMaxTokens)
                    .build();
            case ANTHROPIC -> AnthropicChatOptions.builder()
                    .model(profileConfig.getModel())
                    .temperature(effectiveTemperature)
                    .maxTokens(effectiveMaxTokens)
                    .build();
        };
    }

    private ChatClient buildClient(String profile) {
        LlmProperties.Profile profileConfig = requireProfile(profile);
        LlmProperties.Provider provider = requireProvider(profile, profileConfig);
        RestClient.Builder restClient = hardenedRestClient(profileConfig);

        ChatClient chatClient = switch (protocolOf(provider)) {
            case OPENAI -> buildOpenAiClient(profileConfig, provider, restClient);
            case ANTHROPIC -> buildAnthropicClient(profileConfig, provider, restClient);
        };
        log.info("[llm] 构建 ChatClient profile={} protocol={} baseUrl={} model={}",
                profile, provider.getProtocol(), provider.getBaseUrl(), profileConfig.getModel());
        return chatClient;
    }

    private ChatClient buildOpenAiClient(LlmProperties.Profile profileConfig,
                                         LlmProperties.Provider provider,
                                         RestClient.Builder restClient) {
        OpenAiApi api = OpenAiApi.builder()
                .baseUrl(provider.getBaseUrl())
                .apiKey(provider.getApiKey())
                .completionsPath(pathOrDefault(provider.getCompletionsPath(), OPENAI_DEFAULT_PATH))
                .restClientBuilder(restClient)
                .build();
        OpenAiChatOptions options = OpenAiChatOptions.builder()
                .model(profileConfig.getModel())
                .temperature(defaultValue(profileConfig.getTemperature(), 0.3))
                .maxTokens(defaultInt(profileConfig.getMaxTokens(), 2048))
                .build();
        OpenAiChatModel model = OpenAiChatModel.builder()
                .openAiApi(api)
                .defaultOptions(options)
                .build();
        return ChatClient.builder(model).build();
    }

    private ChatClient buildAnthropicClient(LlmProperties.Profile profileConfig,
                                            LlmProperties.Provider provider,
                                            RestClient.Builder restClient) {
        AnthropicApi api = AnthropicApi.builder()
                .baseUrl(provider.getBaseUrl())
                .apiKey(provider.getApiKey())
                .completionsPath(pathOrDefault(provider.getCompletionsPath(), ANTHROPIC_DEFAULT_PATH))
                .anthropicVersion(defaultString(provider.getAnthropicVersion(), "2023-06-01"))
                .restClientBuilder(restClient)
                .build();
        AnthropicChatOptions options = AnthropicChatOptions.builder()
                .model(profileConfig.getModel())
                .temperature(defaultValue(profileConfig.getTemperature(), 0.3))
                .maxTokens(defaultInt(profileConfig.getMaxTokens(), 2048))
                .build();
        AnthropicChatModel model = AnthropicChatModel.builder()
                .anthropicApi(api)
                .defaultOptions(options)
                .build();
        return ChatClient.builder(model).build();
    }

    /**
     * 为单次调用加 HTTP 层连接/读取超时，避免 Future 超时后请求长期悬挂。
     */
    private RestClient.Builder hardenedRestClient(LlmProperties.Profile profileConfig) {
        int timeoutSeconds = defaultInt(profileConfig.getTimeoutSeconds(), 120);
        HttpClient httpClient = HttpClient.newBuilder()
                .connectTimeout(CONNECT_TIMEOUT)
                .build();
        JdkClientHttpRequestFactory requestFactory = new JdkClientHttpRequestFactory(httpClient);
        requestFactory.setReadTimeout(Duration.ofSeconds(timeoutSeconds));
        return restClientBuilder.clone().requestFactory(requestFactory);
    }

    private LlmProperties.Profile requireProfile(String profile) {
        LlmProperties.Profile profileConfig = properties.profile(profile);
        if (profileConfig == null) {
            throw LlmException.notConfigured("未配置 LLM profile：" + profile);
        }
        if (profileConfig.getModel() == null || profileConfig.getModel().isBlank()) {
            throw LlmException.notConfigured("LLM profile 缺少 model：" + profile);
        }
        return profileConfig;
    }

    private LlmProperties.Provider requireProvider(String profile, LlmProperties.Profile profileConfig) {
        String providerName = profileConfig.getProvider();
        if (providerName == null || providerName.isBlank()) {
            throw LlmException.notConfigured("LLM profile 缺少 provider：" + profile);
        }
        LlmProperties.Provider provider = properties.provider(providerName);
        if (provider == null) {
            throw LlmException.notConfigured("LLM provider 未配置：" + providerName);
        }
        if (provider.getBaseUrl() == null || provider.getBaseUrl().isBlank()) {
            throw LlmException.notConfigured("LLM provider 缺少 base-url：" + providerName);
        }
        if (provider.getApiKey() == null || provider.getApiKey().isBlank()) {
            throw LlmException.notConfigured(
                    "LLM provider 缺少 api-key：" + providerName + "（请设置对应环境变量）");
        }
        return provider;
    }

    private LlmProtocol protocolOf(LlmProperties.Provider provider) {
        return provider.getProtocol() == null ? LlmProtocol.OPENAI : provider.getProtocol();
    }

    private String pathOrDefault(String path, String defaultPath) {
        return path == null || path.isBlank() ? defaultPath : path;
    }

    private String defaultString(String value, String defaultValue) {
        return value == null || value.isBlank() ? defaultValue : value;
    }

    private double defaultValue(Double value, double defaultValue) {
        return value == null ? defaultValue : value;
    }

    private int defaultInt(Integer value, int defaultValue) {
        return value == null ? defaultValue : value;
    }
}
