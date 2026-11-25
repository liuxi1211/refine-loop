package com.arthur.refineloop.data.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import java.time.LocalDateTime;

/**
 * 系统层-数据接入作业日志（表 {@code ingest_log}）。
 *
 * <p>状态机：PENDING → RUNNING → SUCCESS / FAILED / SKIPPED；同一次编排以 {@code runId} 分组。
 * 字段与 {@code db/schema.sql} 一一对应（MyBatis-Plus 下划线转驼峰映射）。
 */
@TableName("ingest_log")
public class IngestLogEntity {

    @TableId(type = IdType.AUTO)
    private Long id;

    private String runId;
    private String tableName;
    /** FULL / INCR / BACKFILL */
    private String jobType;
    private String rangeParams;
    /** PENDING / RUNNING / SUCCESS / FAILED / SKIPPED */
    private String status;
    private Long rowsFetched;
    private Long rowsWritten;
    private Integer retryCount;
    private LocalDateTime startTime;
    private LocalDateTime endTime;
    private Long durationMs;
    private String errorMsg;
    private LocalDateTime createdAt;

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public String getRunId() {
        return runId;
    }

    public void setRunId(String runId) {
        this.runId = runId;
    }

    public String getTableName() {
        return tableName;
    }

    public void setTableName(String tableName) {
        this.tableName = tableName;
    }

    public String getJobType() {
        return jobType;
    }

    public void setJobType(String jobType) {
        this.jobType = jobType;
    }

    public String getRangeParams() {
        return rangeParams;
    }

    public void setRangeParams(String rangeParams) {
        this.rangeParams = rangeParams;
    }

    public String getStatus() {
        return status;
    }

    public void setStatus(String status) {
        this.status = status;
    }

    public Long getRowsFetched() {
        return rowsFetched;
    }

    public void setRowsFetched(Long rowsFetched) {
        this.rowsFetched = rowsFetched;
    }

    public Long getRowsWritten() {
        return rowsWritten;
    }

    public void setRowsWritten(Long rowsWritten) {
        this.rowsWritten = rowsWritten;
    }

    public Integer getRetryCount() {
        return retryCount;
    }

    public void setRetryCount(Integer retryCount) {
        this.retryCount = retryCount;
    }

    public LocalDateTime getStartTime() {
        return startTime;
    }

    public void setStartTime(LocalDateTime startTime) {
        this.startTime = startTime;
    }

    public LocalDateTime getEndTime() {
        return endTime;
    }

    public void setEndTime(LocalDateTime endTime) {
        this.endTime = endTime;
    }

    public Long getDurationMs() {
        return durationMs;
    }

    public void setDurationMs(Long durationMs) {
        this.durationMs = durationMs;
    }

    public String getErrorMsg() {
        return errorMsg;
    }

    public void setErrorMsg(String errorMsg) {
        this.errorMsg = errorMsg;
    }

    public LocalDateTime getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(LocalDateTime createdAt) {
        this.createdAt = createdAt;
    }
}
