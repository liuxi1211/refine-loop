package com.arthur.refineloop.tushare.client;

/**
 * Tushare 频次超限（code=40203）。
 *
 * <p>正常应被 {@code RateLimiterRegistry} 在请求发出前拦住；一旦出现说明限流配置偏低，
 * 不在这里重试（重试只会加剧超限），直接抛出交给调用方记录。
 */
public class TushareRateLimitException extends TushareException {

    public TushareRateLimitException(String message) {
        super(message);
    }
}
