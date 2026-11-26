package com.arthur.refineloop.data.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Getter;
import lombok.Setter;

import java.math.BigDecimal;

/**
 * 每日指标（表 {@code daily_basic}）的<b>只读投影</b>。
 *
 * <p>为什么只映射这几列：{@code daily_basic} 是 Tushare 镜像表，完整列契约由
 * {@code tushare.ingest.registry.TushareTableRegistry} 与 {@code db/schema.sql} 维护（三处同步），
 * 能力单元只读「估值分位需要的列」，多映射一列就多一处需要随上游契约变更的地方。
 * 因此本实体<b>不</b>用于写入——写入仍走 {@code TushareTableMapper} 的动态 SQL。
 *
 * <p>单位口径（见 {@code db/schema.sql} 列注释）：{@code total_mv} 为万元，{@code pe_ttm}/{@code pb}/
 * {@code ps_ttm} 为倍，{@code dv_ttm} 为 %。换算与单位标注由使用方（能力单元）负责。
 */
@Getter
@Setter
@TableName("daily_basic")
public class DailyBasicEntity {

    /** 股票代码，如 {@code 000001.SZ}。 */
    private String tsCode;

    /** 交易日 {@code yyyyMMdd}。 */
    private String tradeDate;

    /** 收盘价（元）。 */
    private BigDecimal close;

    /** 市盈率（TTM，倍）；≤0 表示亏损样本。 */
    private BigDecimal peTtm;

    /** 市净率（倍）。 */
    private BigDecimal pb;

    /** 市销率（TTM，倍）。 */
    private BigDecimal psTtm;

    /** 股息率（TTM，%）。 */
    private BigDecimal dvTtm;

    /** 总市值（万元）。 */
    private BigDecimal totalMv;
}
