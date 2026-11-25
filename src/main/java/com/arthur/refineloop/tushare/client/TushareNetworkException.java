package com.arthur.refineloop.tushare.client;

/** Tushare 网络/5xx 异常（指数退避重试 3 次后仍失败时抛出）。 */
public class TushareNetworkException extends TushareException {

    public TushareNetworkException(String message) {
        super(message);
    }

    public TushareNetworkException(String message, Throwable cause) {
        super(message, cause);
    }
}
