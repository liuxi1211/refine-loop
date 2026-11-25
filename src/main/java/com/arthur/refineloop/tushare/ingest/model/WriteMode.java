package com.arthur.refineloop.tushare.ingest.model;

import com.arthur.refineloop.tushare.ingest.service.IngestTableWriter;
import com.arthur.refineloop.common.dict.DictEnum;
import com.arthur.refineloop.common.dict.DictTypes;

/**
 * 落库写入模式（由 {@link IngestTableWriter} 按模式选择 SQL 语义）。
 *
 * <p>与《Tushare 接口对接手册》附录 C 的幂等策略一一对应：
 * <ul>
 *   <li>{@link #UPSERT}：有业务唯一键，{@code INSERT ... ON CONFLICT (键) DO UPDATE SET 列 = excluded.列}；</li>
 *   <li>{@link #BLOCK_DELETE_INSERT}：无唯一键的整块删插表，按删除键先删后插（top_list/pledge_detail/repurchase/top10_* 等）；</li>
 *   <li>{@link #FULL_REPLACE}：维表全量替换，同事务清空后批量插入。</li>
 * </ul>
 *
 * <p>同时实现 {@link DictEnum}（字典类型 {@code writeMode}）：前端/运维台要展示或筛选写入模式时，
 * 直接取字典，不再维护第二份中文映射。{@code code} 沿用枚举名，与注册表 {@code TableDef} 的现有口径一致。
 */
public enum WriteMode implements DictEnum {

    /** 幂等 upsert（唯一键 = 注册表幂等键）。 */
    UPSERT("幂等 UPSERT"),

    /** 整块先删后插（按注册表幂等键删除后插入）。 */
    BLOCK_DELETE_INSERT("整块删插"),

    /** 全量替换（维表，同事务 DELETE 全表 + 批量插入）。 */
    FULL_REPLACE("全量替换");

    private final String label;

    WriteMode(String label) {
        this.label = label;
    }

    @Override
    public String dictType() {
        return DictTypes.WRITE_MODE;
    }

    /** 字典/接口取值沿用枚举名（与既有注册表、日志口径一致）。 */
    @Override
    public String code() {
        return name();
    }

    @Override
    public String label() {
        return label;
    }

    @Override
    public int sort() {
        return ordinal() * 10;
    }
}
