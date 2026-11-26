package com.arthur.refineloop.data.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Getter;
import lombok.Setter;

/**
 * 申万行业成分（表 {@code sw_industry_member}）的<b>只读投影</b>。
 *
 * <p>用途只有两个：①估值分位算「申万一级同业中位数」；②判断存档时固化 {@code benchmark.industry_code}
 * （能力详案 3.3 契约 7：禁止归因时按 {@code stock_basic} 事后映射，行业归属会随时间漂移）。
 * 因此只映射 {@code ts_code} 与 L1 层级字段。
 *
 * <p>该表是 Tushare {@code index_member_all} 的全量替换表（无幂等唯一键，见手册附录 C）：
 * 每次拉取整块重写，所以「当前归属」只能按 {@code out_date} 判断，不能假定每个 ts_code 只有一行。
 */
@Getter
@Setter
@TableName("sw_industry_member")
public class SwIndustryMemberEntity {

    /** 股票代码。 */
    private String tsCode;

    /** 申万一级行业代码，如 {@code 801780.SI}。 */
    private String l1Code;

    /** 申万一级行业名称。 */
    private String l1Name;

    /** 纳入日期 {@code yyyyMMdd}。 */
    private String inDate;

    /** 剔除日期 {@code yyyyMMdd}；为空表示仍在成分内。 */
    private String outDate;
}
