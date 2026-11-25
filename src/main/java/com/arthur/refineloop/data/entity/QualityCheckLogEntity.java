package com.arthur.refineloop.data.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import java.time.LocalDateTime;

/**
 * 系统层-数据质量门检查历史（表 {@code quality_check_log}）。
 *
 * <p>{@code items} 在 SQLite 中以 TEXT 存 JSON 文本（JSON1 可查询），应用侧用 Jackson 序列化。
 */
@TableName("quality_check_log")
public class QualityCheckLogEntity {

    @TableId(type = IdType.AUTO)
    private Long id;

    private LocalDateTime checkTime;
    /** MANUAL / ORCHESTRATED / RETRY */
    private String triggerType;
    /** EMPTY / PASS / PASS_WITH_WARN / FAIL */
    private String result;
    /** JSON：{"tradeDate":"yyyyMMdd","items":[...]} */
    private String items;
    private Long durationMs;

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public LocalDateTime getCheckTime() {
        return checkTime;
    }

    public void setCheckTime(LocalDateTime checkTime) {
        this.checkTime = checkTime;
    }

    public String getTriggerType() {
        return triggerType;
    }

    public void setTriggerType(String triggerType) {
        this.triggerType = triggerType;
    }

    public String getResult() {
        return result;
    }

    public void setResult(String result) {
        this.result = result;
    }

    public String getItems() {
        return items;
    }

    public void setItems(String items) {
        this.items = items;
    }

    public Long getDurationMs() {
        return durationMs;
    }

    public void setDurationMs(Long durationMs) {
        this.durationMs = durationMs;
    }
}
