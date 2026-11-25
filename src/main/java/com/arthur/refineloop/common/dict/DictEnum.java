package com.arthur.refineloop.common.dict;

/**
 * 字典枚举契约：业务代码里的「常量组」只要实现本接口，就会自动出现在
 * {@code GET /api/common/dicts} 的返回值里，前端下拉框/标签无需再硬编码文案。
 *
 * <p>使用步骤（新增一个字典）：
 * <ol>
 *   <li>在所属域包里新建枚举并实现 {@link DictEnum}；</li>
 *   <li>{@link #dictType()} 返回 {@link DictTypes} 里登记的稳定编码——<b>不要</b>直接写死字符串，
 *       更不要用类名派生（改类名会静默打断前端）；</li>
 *   <li>{@link #code()} 是落库/传输值（稳定，不可改）；{@link #label()} 是展示文案
 *       （可改，改完前端自动跟着变）；</li>
 *   <li>{@link #color()} 可选，取值与前端 chip 色调一致：{@code ok/warn/err/idle/run/off}。</li>
 * </ol>
 *
 * <p>约定：{@code code} 一律字符串。数值型开关（0/1）也用 {@code "0"/"1"} 表达——字典的职责是
 * 「展示与选择的稳定映射」，不是承载类型；后端接参用 String + {@code Enums} 解析，前端选择后按需转数字。
 */
public interface DictEnum {

    /** 字典类型编码（前端按此取数，全局唯一且稳定，登记在 {@link DictTypes}）。 */
    String dictType();

    /** 字典项编码（落库/传输值，发布后不可修改）。 */
    String code();

    /** 展示文案（中文，可修改）。 */
    String label();

    /** 排序值，升序；相同值按 label 排序。 */
    default int sort() {
        return 0;
    }

    /** 前端 chip 色调：{@code ok / warn / err / idle / run / off}；null 表示前端默认灰色。 */
    default String color() {
        return null;
    }

    /** 是否启用；停用项仍会下发（前端可置灰），但后端校验会拒绝新写入。 */
    default boolean enabled() {
        return true;
    }
}
