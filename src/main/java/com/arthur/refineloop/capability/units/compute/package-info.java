/**
 * compute 单元（L1 计算层）：纯代码、无 LLM、确定性、可单测。
 *
 * <p>M1 的 compute 单元全部为 {@code UnitScope.BATCH}：一次执行处理整个标的池，
 * 单元内部对池分页读历史序列（见 {@code PAGE_SIZE}），产出的批量事实由调用方落 {@code unit_fact}。
 *
 * <p><b>不变量</b>：同输入必同输出；不写库、不开事务、不读 {@code LocalDate.now()}；
 * 每个标的的输出是「当前值 + 分位/区间 + 口径说明 + 降级说明」的事实表，而不是结论。
 */
package com.arthur.refineloop.capability.units.compute;
