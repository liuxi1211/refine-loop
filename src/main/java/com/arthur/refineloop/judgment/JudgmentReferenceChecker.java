package com.arthur.refineloop.judgment;

/**
 * 「判断是否已被归因引用」的检查口：重跑语义的前置闸门（能力详案 3.3 契约 5）。
 *
 * <p><b>为什么要抽成接口</b>：M1 只做存档，归因（待归因记录、复盘引用）在 M3 落地，因此 M1 的实现
 * 恒返回 false。把它做成接口而不是写死 {@code return false}，是为了让 M3 只需替换一个 Bean
 * ——存档器（本项目最需要稳定的代码）一行不改。
 *
 * <p><b>契约</b>：返回 true 时存档器必须拒绝自动重跑（6003），由人工标注失效后再次触发。
 * 语义是「这份判断已经被下游引用过，改写它会破坏归因链路」，不是「这份判断很重要」。
 */
@FunctionalInterface
public interface JudgmentReferenceChecker {

    /**
     * 判断是否已被归因/待归因流程引用。
     *
     * @param judgmentId 判断编号
     * @return true 表示禁止自动重跑覆盖
     */
    boolean isReferenced(String judgmentId);

    /** M1 的空实现：尚无归因表，任何判断都未被引用。M3 换 Bean 时删除本方法即可。 */
    static JudgmentReferenceChecker none() {
        return judgmentId -> false;
    }
}
