package com.arthur.refineloop.data.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Getter;
import lombok.Setter;

import java.time.LocalDateTime;

/**
 * 判断层-判断与能力单元引用（表 {@code judgment_unit_ref}）。
 *
 * <p>每份主判断的每个参与单元一行，记录 {@code unit_id + unit_version} 与该单元判断块的 JSON 摘要。
 * 归因（M3）靠它把「方法因素」定位到具体单元的某个版本——这是判断资产可解释性的最小粒度。
 *
 * <p>幂等键 {@code (judgment_id, unit_id)}：同一判断内同一单元只允许一条（重复意味着编排缺陷）。
 */
@Getter
@Setter
@TableName("judgment_unit_ref")
public class JudgmentUnitRefEntity {

    private Long id;

    /** 判断编号（{@code judgment.judgment_id}）。 */
    private String judgmentId;

    /** 能力单元 id。 */
    private String unitId;

    /** 单元版本（如 {@code v1.0}）。 */
    private String unitVersion;

    /** compute 单元对应的 {@code unit_fact.id}；interpret 单元无事实行时为 null。 */
    private Long factId;

    /** 单元判断块 JSON（{@code facts_used} 溯源弹层的数据源）。 */
    private String outputSummary;

    private LocalDateTime createdAt;
}
