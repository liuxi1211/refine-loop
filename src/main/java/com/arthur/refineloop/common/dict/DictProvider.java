package com.arthur.refineloop.common.dict;

import java.util.List;

/**
 * 动态字典提供者（SPI）：枚举表达不了的字典（来自数据库/配置/外部接口）实现本接口，
 * 声明为 Spring Bean 后会被 {@code DictService} 自动汇总，与枚举字典合并下发。
 *
 * <p>约束：
 * <ul>
 *   <li>{@link #dictType()} 不得与已登记的枚举类型重复（启动期校验，冲突直接启动失败）；</li>
 *   <li>{@link #items()} 应是幂等的全量快照，内部自行处理缓存与异常——<b>不要</b>抛出异常让整个
 *       字典接口 500：局部字典不可用时应返回空列表并记 warn 日志；</li>
 *   <li>刷新语义：{@code POST /api/common/dicts/refresh} 会重新调用本方法。</li>
 * </ul>
 */
public interface DictProvider {

    /** 字典类型编码（登记在 {@link DictTypes}）。 */
    String dictType();

    /** 全量字典项（可含停用项）。 */
    List<DictItem> items();
}
