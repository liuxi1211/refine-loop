package com.arthur.refineloop.data.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Getter;
import lombok.Setter;

import java.time.LocalDateTime;

/**
 * 判断层-判断存档（表 {@code judgment}）：本项目最核心的资产，<b>内容不可改</b>。
 *
 * <p><b>为什么有两个字段不在这里</b>：
 * <ul>
 *   <li>{@code active_flag} 是数据库生成列（{@code GENERATED ALWAYS AS (CASE WHEN superseded = 0 THEN 1 END) STORED}），
 *       写进来会直接报错——它的存在是为了让唯一索引 {@code uk_judgment_active} 能表达
 *       「同 (ts_code, trade_date, pipeline_id) 至多一行 superseded=0」；</li>
 *   <li>{@code updated_at} 本表没有：冻结语义下不存在「内容更新时间」，唯一的可变列是
 *       {@code superseded} 与 {@code invalidated_at}，由 {@code JudgmentMapper} 的专用方法写（不暴露通用 update）。</li>
 * </ul>
 *
 * <p><b>时间口径</b>：{@code expire_at} 是判断有效期，库内是 {@code DATE} 列但 SQLite 以文本存储，
 * 实体用 {@code yyyy-MM-dd} 字符串承载（T2 已登记该列的类型例外 E6）；{@code invalidated_at}/{@code created_at}
 * 是系统时间，用 {@link LocalDateTime}。
 */
@Getter
@Setter
@TableName("judgment")
public class JudgmentEntity {

    /** 自增主键（仅库内用，业务标识是 judgment_id）。 */
    private Long id;

    /** 判断编号，形如 {@code J-20261122-P1-0001}（唯一）。 */
    private String judgmentId;

    /** 交易日 {@code yyyyMMdd}。 */
    private String tradeDate;

    /** 标的代码。 */
    private String tsCode;

    /** 流水线 id（P1 / P1-MANUAL…）。 */
    private String pipelineId;

    /** 关联报告主键（{@code report.id}）；降级判断指向 DEGRADED 占位报告。 */
    private Long reportId;

    /** 方法版本（判断存档时冻结，归因的统计分组键）。 */
    private String methodVersion;

    /** 冗余列：方向（bullish/bearish/neutral/conditional），供 SQL 统计，避免解析 JSON。 */
    private String direction;

    /** 冗余列：判断有效期（交易日）。 */
    private Integer horizonDays;

    /** 判断到期日 {@code yyyy-MM-dd}（自然日）。 */
    private String expireAt;

    /** 冗余列：置信度（高/中/低）。 */
    private String confidence;

    /** 能力详案 3.2 完整 Schema 的 JSON 文本（冻结后不参与任何 UPDATE）。 */
    private String payload;

    /** 存档即冻结，恒为 1。 */
    private Integer frozen;

    /** 是否已被同日重跑取代（1 = 已被取代，其 active_flag 为 NULL）。 */
    private Integer superseded;

    /** 失效时间（唯一允许更新的时间列之一）。 */
    private LocalDateTime invalidatedAt;

    /** 写入时间。 */
    private LocalDateTime createdAt;
}
