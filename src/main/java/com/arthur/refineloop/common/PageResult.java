package com.arthur.refineloop.common;

import java.util.List;
import java.util.function.Function;

/**
 * 统一分页结果（{@code page} 从 1 开始，{@code size} 为每页条数）。
 *
 * <p>与前端 {@code PageResult<T>}（frontend/src/api/types.ts）字段逐一对应；
 * MyBatis-Plus 分页对象转换见 {@code common.page.PageResults}。
 */
public record PageResult<T>(List<T> items, long total, int page, int size) {

    public static <T> PageResult<T> of(List<T> items, long total, int page, int size) {
        return new PageResult<>(items == null ? List.of() : items, total, page, size);
    }

    public static <T> PageResult<T> empty(int page, int size) {
        return new PageResult<>(List.of(), 0L, page, size);
    }

    /** 转换条目类型（如 Entity → View），分页信息不变。 */
    public <R> PageResult<R> map(Function<? super T, ? extends R> mapper) {
        List<R> mapped = items.stream().<R>map(mapper).toList();
        return new PageResult<>(mapped, total, page, size);
    }

    public boolean hasNext() {
        return (long) page * size < total;
    }

    public int totalPages() {
        return size <= 0 ? 0 : (int) ((total + size - 1) / size);
    }
}
