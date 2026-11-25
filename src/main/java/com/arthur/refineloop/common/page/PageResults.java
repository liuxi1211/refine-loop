package com.arthur.refineloop.common.page;

import com.arthur.refineloop.common.PageResult;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;

/**
 * MyBatis-Plus 分页对象 ↔ {@link PageResult} 的唯一转换点。
 *
 * <p>为什么要这层：MP 的 {@code Page} 带 current/size/records/total/pages 等一堆字段，
 * 直接返回给前端会把 ORM 细节泄进 API 契约；本类负责「只在 web 层做一次转换」。
 *
 * <p>注意：{@code IPage} 只有在注册了 {@code PaginationInnerInterceptor} 时才会真正生成
 * LIMIT/OFFSET（见 {@code config.MyBatisPlusConfig}），否则会静默全表返回。
 */
public final class PageResults {

    private PageResults() {
    }

    /** MP 分页查询结果 → 对外分页结果。 */
    public static <T> PageResult<T> of(IPage<T> page) {
        return PageResult.of(page.getRecords(), page.getTotal(), (int) page.getCurrent(), (int) page.getSize());
    }

    /** 手工分页（动态 SQL / 聚合查询）→ 对外分页结果。 */
    public static <T> PageResult<T> of(java.util.List<T> items, long total, PageQuery query) {
        return PageResult.of(items, total, query.page(), query.size());
    }

    public static <T> PageResult<T> empty(PageQuery query) {
        return PageResult.empty(query.page(), query.size());
    }

    /** 统一入口：构造 MP 分页参数（含校验后的 page/size）。 */
    public static <T> Page<T> toPage(PageQuery query) {
        return new Page<>(query.page(), query.size());
    }
}
