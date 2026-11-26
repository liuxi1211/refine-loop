package com.arthur.refineloop.capability.units.compute;

/**
 * {@code calc_valuation_percentile} 的事实表契约：指标路径、单位口径、状态与阈值常量。
 *
 * <p><b>为什么要有这个类</b>：下游（interpret 单元、溯源弹层、测试）引用事实指标时写的是
 * {@code percentile_3y.pe_ttm} 这类点号路径字符串。字符串散落在三处必然漂移——单元改了一层嵌套，
 * 校验器就报「编造指标」而没人知道是拼错还是改结构。这里把路径、单位、状态各收敛成一份常量。
 *
 * <p><b>路径的生成规则</b>（由 {@code FactMetrics} 摊平）：payload 的嵌套键用 {@code .} 连接，
 * 数组用 {@code [i]}；只有含 {@code value} 键的 Map 才算指标叶子，因此
 * {@code percentile_3y.method="empirical_cdf"} 不是指标，{@code percentile_3y.pe_ttm} 是。
 *
 * <p><b>单位口径</b>（能力详案 5.4）：{@code pe_ttm/pb/ps_ttm}=倍、{@code dv_ttm}=pct（百分数）、
 * 分位与 band=pct（0~100 的百分数，不是 0~1 的比例）、比率类=ratio、市值=亿元、样本天数=交易日。
 * 单位写进事实叶子是 L3 校验「数值 + 单位双比对」的前提（能力详案 3.3 契约 9）。
 */
public final class ValuationFactSchema {

    /** 单元 id（与 {@code @CapabilityUnit(id=...)} 及资源目录名一致）。 */
    public static final String UNIT_ID = "calc_valuation_percentile";

    /** 单元版本。 */
    public static final String VERSION = "v1.0";

    /** 事实 Schema 版本（随单元主版本冻结）。 */
    public static final String SCHEMA_VERSION = "v1.0";

    /* ---------------- 指标路径（FactMetrics 摊平后的引用名） ---------------- */

    public static final String CURRENT_PE_TTM = "current.pe_ttm";
    public static final String CURRENT_PB = "current.pb";
    public static final String CURRENT_PS_TTM = "current.ps_ttm";
    public static final String CURRENT_DV_TTM = "current.dv_ttm";
    public static final String CURRENT_TOTAL_MV_YI = "current.total_mv_yi";

    public static final String PERCENTILE_PE_TTM = "percentile_3y.pe_ttm";
    public static final String PERCENTILE_PB = "percentile_3y.pb";
    public static final String PERCENTILE_PS_TTM = "percentile_3y.ps_ttm";

    public static final String BAND_PE_TTM_P10 = "band_3y.pe_ttm.p10";
    public static final String BAND_PE_TTM_P25 = "band_3y.pe_ttm.p25";
    public static final String BAND_PE_TTM_P50 = "band_3y.pe_ttm.p50";
    public static final String BAND_PE_TTM_P75 = "band_3y.pe_ttm.p75";
    public static final String BAND_PE_TTM_P90 = "band_3y.pe_ttm.p90";

    public static final String LOSS_RATIO = "loss_ratio_3y";
    public static final String INDUSTRY_PE_MEDIAN = "relative_industry.pe_ttm_median";
    public static final String VS_INDUSTRY = "relative_industry.vs_industry";
    public static final String SAMPLE_DAYS = "window.sample_days";

    /* ---------------- 单位口径 ---------------- */

    /** 倍数口径（PE/PB/PS 及其分位区间的原始值）。 */
    public static final String UNIT_MULTIPLE = "倍";
    /** 百分数口径（0~100）。 */
    public static final String UNIT_PCT = "pct";
    /** 比率口径（0~1 或任意小数比值）。 */
    public static final String UNIT_RATIO = "ratio";
    /** 市值口径：亿元（{@code daily_basic.total_mv} 为万元，除以 10000）。 */
    public static final String UNIT_YI_YUAN = "亿元";
    /** 交易日天数口径。 */
    public static final String UNIT_TRADE_DAYS = "交易日";

    /* ---------------- payload 内的 data_status 取值（详案 5.4 示例口径） ---------------- */

    public static final String STATUS_OK = "ok";
    public static final String STATUS_DEGRADED = "degraded";
    public static final String STATUS_MISSING = "missing";

    /* ---------------- 计算常量 ---------------- */

    /** 历史窗口长度（自然年）。 */
    public static final int WINDOW_YEARS = 3;

    /**
     * 「不足两年即降级为仅当前值、无分位」的最小**交易日**数（详案 5.3）。
     *
     * <p>取 480 而非 490：一年约 244 个交易日，两年 488；再留 8 天余量吸收窗口首尾的缺行，
     * 避免「刚好两年但缺两天」被误降级。
     */
    public static final int MIN_SAMPLE_DAYS = 480;

    private ValuationFactSchema() {
    }
}
