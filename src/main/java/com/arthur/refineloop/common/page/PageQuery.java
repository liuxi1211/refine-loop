package com.arthur.refineloop.common.page;

import com.arthur.refineloop.common.ApiException;

/**
 * 统一分页请求参数（{@code page} 从 1 开始）。
 *
 * <p>校验语义与既有运维 API 保持一致：{@code page < 1} 或 {@code size} 超出
 * {@code 1..MAX_SIZE} 直接 400，<b>不做静默截断</b>——静默修正会让前端「分页越界却看到第 1 页」，
 * 排查成本远高于一次明确报错。
 *
 * <p>排序参数不在这里：按字段排序必须走白名单（防 SQL 注入），由各查询自行校验列名。
 */
public record PageQuery(int page, int size) {

    public static final int DEFAULT_PAGE = 1;
    public static final int DEFAULT_SIZE = 20;
    public static final int MAX_SIZE = 100;

    public PageQuery {
        if (page < 1) {
            throw ApiException.badRequest("page 必须 ≥ 1（当前 " + page + "）");
        }
        if (size < 1 || size > MAX_SIZE) {
            throw ApiException.badRequest("size 必须为 1~" + MAX_SIZE + "（当前 " + size + "）");
        }
    }

    /** 控制器便捷入口：null → 默认值，非 null 走同一套校验。 */
    public static PageQuery of(Integer page, Integer size) {
        return new PageQuery(
                page == null ? DEFAULT_PAGE : page,
                size == null ? DEFAULT_SIZE : size);
    }

    public static PageQuery firstPage() {
        return new PageQuery(DEFAULT_PAGE, DEFAULT_SIZE);
    }

    /** SQL OFFSET。 */
    public long offset() {
        return (long) (page - 1) * size;
    }

    /** SQL LIMIT。 */
    public int limit() {
        return size;
    }
}
