package com.arthur.refineloop.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Tushare 接入配置（前缀 {@code tushare}）。
 *
 * <p>token 只从环境变量 {@code TUSHARE_TOKEN} 注入（application.yml 中为占位引用），
 * 不写入仓库、不打印日志。
 */
@ConfigurationProperties(prefix = "tushare")
public class TushareProperties {

    /** Tushare HTTP 端点（可配，便于单测/代理切换）。 */
    private String baseUrl = "http://api.tushare.pro";

    /** Tushare Pro token，只从环境变量注入；为空时调用会抛明确异常。 */
    private String token = "";

    /** 网络/5xx 指数退避重试次数（默认 3 次，即最多 4 次请求）。 */
    private int retryCount = 3;

    /** 首次重试等待毫秒（1s/2s/4s 指数退避），测试可设为 0/1。 */
    private long retryBackoffMillis = 1000L;

    public String getBaseUrl() {
        return baseUrl;
    }

    public void setBaseUrl(String baseUrl) {
        this.baseUrl = baseUrl;
    }

    public String getToken() {
        return token;
    }

    public void setToken(String token) {
        this.token = token;
    }

    public int getRetryCount() {
        return retryCount;
    }

    public void setRetryCount(int retryCount) {
        this.retryCount = retryCount;
    }

    public long getRetryBackoffMillis() {
        return retryBackoffMillis;
    }

    public void setRetryBackoffMillis(long retryBackoffMillis) {
        this.retryBackoffMillis = retryBackoffMillis;
    }
}
