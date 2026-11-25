package com.arthur.refineloop.data.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

/** 系统层-限流分钟计数（表 {@code rate_limit_counter}，{@code (api_name, minute_slot)} 唯一）。 */
@TableName("rate_limit_counter")
public class RateLimitCounterEntity {

    @TableId(type = IdType.AUTO)
    private Long id;

    private String apiName;
    /** yyyyMMddHHmm */
    private String minuteSlot;
    private Integer callCount;

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public String getApiName() {
        return apiName;
    }

    public void setApiName(String apiName) {
        this.apiName = apiName;
    }

    public String getMinuteSlot() {
        return minuteSlot;
    }

    public void setMinuteSlot(String minuteSlot) {
        this.minuteSlot = minuteSlot;
    }

    public Integer getCallCount() {
        return callCount;
    }

    public void setCallCount(Integer callCount) {
        this.callCount = callCount;
    }
}
