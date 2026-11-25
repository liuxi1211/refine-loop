package com.arthur.refineloop.common.util;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.BiConsumer;
import java.util.function.Function;

/**
 * 树形结构构建：申万行业（一级/二级/三级）、报告章节、分类字典都用得上。
 *
 * <p>实现约定：
 * <ul>
 *   <li>保持输入顺序（用 {@link LinkedHashMap}），不额外排序——调用方先排好再传，结果可预期；</li>
 *   <li>父节点不存在 / 父 ID 等于自身 ID 的节点当作根节点，<b>不丢数据</b>（宁可多一个根，也不能静默丢节点）；</li>
 *   <li>不修改原对象集合，只通过 {@code childrenSetter} 挂子节点。</li>
 * </ul>
 */
public final class Trees {

    private Trees() {
    }

    /**
     * 构建树并返回根节点列表。
     *
     * @param nodes          扁平节点（顺序即最终同级顺序）
     * @param idGetter       节点 ID 提取
     * @param parentGetter   父 ID 提取
     * @param childrenSetter 子节点挂载（通常传 {@code T::setChildren}）
     */
    public static <T, ID> List<T> build(Collection<T> nodes,
                                        Function<T, ID> idGetter,
                                        Function<T, ID> parentGetter,
                                        BiConsumer<T, List<T>> childrenSetter) {
        if (nodes == null || nodes.isEmpty()) {
            return List.of();
        }
        Map<ID, T> byId = new LinkedHashMap<>();
        for (T node : nodes) {
            ID id = idGetter.apply(node);
            if (id != null) {
                byId.putIfAbsent(id, node);
            }
        }
        Map<ID, List<T>> children = new LinkedHashMap<>();
        List<T> roots = new ArrayList<>();
        for (T node : nodes) {
            ID parentId = parentGetter.apply(node);
            ID selfId = idGetter.apply(node);
            boolean isRoot = parentId == null
                    || parentId.equals(selfId)
                    || !byId.containsKey(parentId);
            if (isRoot) {
                roots.add(node);
            } else {
                children.computeIfAbsent(parentId, key -> new ArrayList<>()).add(node);
            }
        }
        for (T node : nodes) {
            List<T> childNodes = children.get(idGetter.apply(node));
            if (childNodes != null) {
                childrenSetter.accept(node, childNodes);
            }
        }
        return roots;
    }

    /** 扁平化（深度优先，含根），用于「树 → 表格」。 */
    public static <T> List<T> flatten(Collection<T> roots, Function<T, List<T>> childrenGetter) {
        List<T> result = new ArrayList<>();
        if (roots == null) {
            return result;
        }
        for (T root : roots) {
            result.add(root);
            List<T> children = childrenGetter.apply(root);
            if (children != null && !children.isEmpty()) {
                result.addAll(flatten(children, childrenGetter));
            }
        }
        return result;
    }
}
