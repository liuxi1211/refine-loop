package com.arthur.refineloop.data.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Getter;
import lombok.Setter;

import java.time.LocalDateTime;

/**
 * 事实层-能力单元事实（表 {@code unit_fact}）。
 *
 * <p>幂等键 {@code run_id + unit_id + unit_version + ts_code + trade_date}；同一 run 只允许追加，
 * 不同 run 重算产生新行，保证旧判断引用的事实不被覆盖。
 */
@Getter
@Setter
@TableName("unit_fact")
public class UnitFactEntity {

    @TableId(type = IdType.AUTO)
    private Long id;

    private String runId;
    private String unitId;
    private String unitVersion;
    private String schemaVersion;
    private String tsCode;
    private String tradeDate;
    private String payload;
    /** COMPLETE / DEGRADED / MISSING */
    private String dataStatus;
    /** 降级/缺失原因 JSON 数组 */
    private String notes;
    private LocalDateTime createdAt;
}
