package com.arthur.refineloop.tushare.client;

/** Tushare 调用异常基类。 */
public class TushareException extends RuntimeException {

    public TushareException(String message) {
        super(message);
    }

    public TushareException(String message, Throwable cause) {
        super(message, cause);
    }
}
