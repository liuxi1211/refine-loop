package com.arthur.refineloop.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/** T4 数据接入配置（前缀 {@code tushare.ingest}）。 */
@ConfigurationProperties(prefix = "tushare.ingest")
public class IngestProperties {

    /** per-stock 策略并发度（手册建议 ≤30，与限流叠加）。 */
    private int perStockConcurrency = 30;

    /** 全量回填起始业务日期。 */
    private String fullStartDate = "19901219";

    /** 增量起点为空（空表）时向前回退的天数。 */
    private int incrementalFallbackDays = 365;

    /** 批量写库行数（手册建议 500 行/批）。 */
    private int batchSize = 500;

    /** 作业级失败重试次数（指数退避 1s/2s/4s）。 */
    private int jobRetryCount = 3;

    /** 作业级重试首次等待毫秒。 */
    private long jobRetryBackoffMillis = 1000L;

    /** suspend_d 单月 offset 上限（超过后降级 per-stock；手册实测上限 10 万，测试可调小）。 */
    private int suspendOffsetLimit = 100_000;

    public int getPerStockConcurrency() {
        return perStockConcurrency;
    }

    public void setPerStockConcurrency(int perStockConcurrency) {
        this.perStockConcurrency = perStockConcurrency;
    }

    public String getFullStartDate() {
        return fullStartDate;
    }

    public void setFullStartDate(String fullStartDate) {
        this.fullStartDate = fullStartDate;
    }

    public int getIncrementalFallbackDays() {
        return incrementalFallbackDays;
    }

    public void setIncrementalFallbackDays(int incrementalFallbackDays) {
        this.incrementalFallbackDays = incrementalFallbackDays;
    }

    public int getBatchSize() {
        return batchSize;
    }

    public void setBatchSize(int batchSize) {
        this.batchSize = batchSize;
    }

    public int getJobRetryCount() {
        return jobRetryCount;
    }

    public void setJobRetryCount(int jobRetryCount) {
        this.jobRetryCount = jobRetryCount;
    }

    public long getJobRetryBackoffMillis() {
        return jobRetryBackoffMillis;
    }

    public void setJobRetryBackoffMillis(long jobRetryBackoffMillis) {
        this.jobRetryBackoffMillis = jobRetryBackoffMillis;
    }

    public int getSuspendOffsetLimit() {
        return suspendOffsetLimit;
    }

    public void setSuspendOffsetLimit(int suspendOffsetLimit) {
        this.suspendOffsetLimit = suspendOffsetLimit;
    }
}
