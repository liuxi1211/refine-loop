package com.arthur.refineloop.tushare.client;

/** Tushare 其他业务错误（code 非 0/40203/50101）。 */
public class TushareApiException extends TushareException {

    private final int apiCode;

    public TushareApiException(int apiCode, String message) {
        super(message);
        this.apiCode = apiCode;
    }

    public int getApiCode() {
        return apiCode;
    }
}
