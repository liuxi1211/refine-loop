package com.arthur.refineloop.common.dict;

/**
 * 字典类型编码登记表：{@code dictType} 的唯一事实源。
 *
 * <p>为什么不让枚举用 {@code getClass().getSimpleName()}：类名重构（重命名/移动包）会静默改掉
 * 前端已经在用的取值，属于「编译能过、线上才炸」的问题。手写常量虽然啰嗦，但改名时 IDE
 * 会连带改掉引用方，前端契约不会悄悄变化。
 *
 * <p>命名规范：小驼峰、语义化、不带域前缀（{@code jobStatus} 而非 {@code ingestJobStatus}），
 * 确实跨域重名再加域前缀。
 *
 * <p>说明：常量可以先登记、实现（{@code DictEnum} / {@code DictProvider}）后补——编码一旦下发前端
 * 就不能再改，先占位比事后改名便宜。
 */
public final class DictTypes {

    /* ---------------- 通用 ---------------- */
    public static final String YES_NO = "yesNo";
    public static final String ENABLE_STATUS = "enableStatus";
    public static final String SORT_ORDER = "sortOrder";
    public static final String LOG_LEVEL = "logLevel";
    public static final String TIME_RANGE = "timeRange";

    /* ---------------- 账号 ---------------- */
    public static final String USER_ROLE = "userRole";

    /* ---------------- 运维/数据接入 ---------------- */
    public static final String JOB_STATUS = "jobStatus";
    public static final String JOB_TYPE = "jobType";
    public static final String TRIGGER_TYPE = "triggerType";
    public static final String TABLE_HEALTH = "tableHealth";
    public static final String WRITE_MODE = "writeMode";
    public static final String QUALITY_RESULT = "qualityResult";
    public static final String CHECK_ITEM_STATUS = "checkItemStatus";

    /* ---------------- 判断域 ---------------- */
    public static final String JUDGMENT_DIRECTION = "judgmentDirection";
    public static final String CONFIDENCE_LEVEL = "confidenceLevel";

    private DictTypes() {
    }
}
