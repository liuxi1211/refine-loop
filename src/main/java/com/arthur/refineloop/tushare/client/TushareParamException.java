package com.arthur.refineloop.tushare.client;

/**
 * Tushare 参数/查询组合错误（code=50101）。
 *
 * <p>不重试、不告警风暴：直接抛给调用方，由作业层记录 FAILED 明细。
 */
public class TushareParamException extends TushareException {

    private final int apiCode;

    public TushareParamException(int apiCode, String message) {
        super(message);
        this.apiCode = apiCode;
    }

    public int getApiCode() {
        return apiCode;
    }
}
