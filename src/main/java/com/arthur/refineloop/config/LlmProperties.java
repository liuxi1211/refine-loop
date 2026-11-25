package com.arthur.refineloop.config;

import com.arthur.refineloop.capability.runtime.llm.LlmProtocol;
import org.springframework.boot.context.properties.ConfigurationProperties;

import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * LLM 配置：profile → provider（协议 + 端点）→ model。
 *
 * <p>密钥只从环境变量注入（{@code ${DEEPSEEK_API_KEY:}} 等），配置文件不落明文 Key。
 * 切换模型/协议/Base URL 只改 {@code llm.yml} 或命令行参数。
 */
@ConfigurationProperties(prefix = "llm")
public class LlmProperties {

    /** provider 名 → 端点配置。 */
    private Map<String, Provider> providers = new LinkedHashMap<>();

    /** profile 名（fast / strong）→ 模型配置。 */
    private Map<String, Profile> profiles = new LinkedHashMap<>();

    private Retry retry = new Retry();

    /** 启动期真实调用一次 fast 档做连通性冒烟；默认关闭。 */
    private boolean smokeOnStartup = false;

    public Map<String, Provider> getProviders() {
        return providers;
    }

    public void setProviders(Map<String, Provider> providers) {
        this.providers = providers == null ? new LinkedHashMap<>() : providers;
    }

    public Map<String, Profile> getProfiles() {
        return profiles;
    }

    public void setProfiles(Map<String, Profile> profiles) {
        this.profiles = profiles == null ? new LinkedHashMap<>() : profiles;
    }

    public Retry getRetry() {
        return retry;
    }

    public void setRetry(Retry retry) {
        this.retry = retry == null ? new Retry() : retry;
    }

    public boolean isSmokeOnStartup() {
        return smokeOnStartup;
    }

    public void setSmokeOnStartup(boolean smokeOnStartup) {
        this.smokeOnStartup = smokeOnStartup;
    }

    public Profile profile(String name) {
        return profiles.get(name);
    }

    public Provider provider(String name) {
        return providers.get(name);
    }

    /**
     * 端点配置。
     */
    public static class Provider {

        /** OPENAI / ANTHROPIC */
        private LlmProtocol protocol = LlmProtocol.OPENAI;
        private String baseUrl;
        private String apiKey;
        /** OpenAI 默认 /v1/chat/completions；Anthropic 默认 /v1/messages。 */
        private String completionsPath;
        /** Anthropic 版本头，默认 2023-06-01。 */
        private String anthropicVersion = "2023-06-01";

        public LlmProtocol getProtocol() {
            return protocol;
        }

        public void setProtocol(LlmProtocol protocol) {
            this.protocol = protocol;
        }

        public String getBaseUrl() {
            return baseUrl;
        }

        public void setBaseUrl(String baseUrl) {
            this.baseUrl = baseUrl;
        }

        public String getApiKey() {
            return apiKey;
        }

        public void setApiKey(String apiKey) {
            this.apiKey = apiKey;
        }

        public String getCompletionsPath() {
            return completionsPath;
        }

        public void setCompletionsPath(String completionsPath) {
            this.completionsPath = completionsPath;
        }

        public String getAnthropicVersion() {
            return anthropicVersion;
        }

        public void setAnthropicVersion(String anthropicVersion) {
            this.anthropicVersion = anthropicVersion;
        }
    }

    /**
     * 模型档位配置。
     */
    public static class Profile {

        private String provider;
        private String model;
        private Double temperature = 0.3;
        private Integer maxTokens = 2048;
        private Integer timeoutSeconds = 120;
        private BigDecimal inputPricePer1m = BigDecimal.ZERO;
        private BigDecimal outputPricePer1m = BigDecimal.ZERO;

        public String getProvider() {
            return provider;
        }

        public void setProvider(String provider) {
            this.provider = provider;
        }

        public String getModel() {
            return model;
        }

        public void setModel(String model) {
            this.model = model;
        }

        public Double getTemperature() {
            return temperature;
        }

        public void setTemperature(Double temperature) {
            this.temperature = temperature;
        }

        public Integer getMaxTokens() {
            return maxTokens;
        }

        public void setMaxTokens(Integer maxTokens) {
            this.maxTokens = maxTokens;
        }

        public Integer getTimeoutSeconds() {
            return timeoutSeconds;
        }

        public void setTimeoutSeconds(Integer timeoutSeconds) {
            this.timeoutSeconds = timeoutSeconds;
        }

        public BigDecimal getInputPricePer1m() {
            return inputPricePer1m;
        }

        public void setInputPricePer1m(BigDecimal inputPricePer1m) {
            this.inputPricePer1m = inputPricePer1m;
        }

        public BigDecimal getOutputPricePer1m() {
            return outputPricePer1m;
        }

        public void setOutputPricePer1m(BigDecimal outputPricePer1m) {
            this.outputPricePer1m = outputPricePer1m;
        }
    }

    /**
     * 重试与退避配置。
     */
    public static class Retry {

        private int maxAttempts = 3;
        private long initialBackoffMillis = 1000L;
        private double multiplier = 2.0;
        private long rateLimitBackoffMillis = 5000L;

        public int getMaxAttempts() {
            return maxAttempts;
        }

        public void setMaxAttempts(int maxAttempts) {
            this.maxAttempts = maxAttempts;
        }

        public long getInitialBackoffMillis() {
            return initialBackoffMillis;
        }

        public void setInitialBackoffMillis(long initialBackoffMillis) {
            this.initialBackoffMillis = initialBackoffMillis;
        }

        public double getMultiplier() {
            return multiplier;
        }

        public void setMultiplier(double multiplier) {
            this.multiplier = multiplier;
        }

        public long getRateLimitBackoffMillis() {
            return rateLimitBackoffMillis;
        }

        public void setRateLimitBackoffMillis(long rateLimitBackoffMillis) {
            this.rateLimitBackoffMillis = rateLimitBackoffMillis;
        }
    }
}
