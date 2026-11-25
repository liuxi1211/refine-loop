package com.arthur.refineloop.capability.spi;

import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;

/**
 * 标的池：BATCH 单元一次处理的股票集合。
 *
 * @param name    池名称（日志用）
 * @param tsCodes 股票代码，去重保序、不可变
 */
public record StockPool(String name, List<String> tsCodes) {

    public StockPool {
        name = name == null || name.isBlank() ? "UNIVERSE" : name;
        LinkedHashSet<String> unique = new LinkedHashSet<>();
        if (tsCodes != null) {
            for (String code : tsCodes) {
                if (code != null && !code.isBlank()) {
                    unique.add(code);
                }
            }
        }
        tsCodes = List.copyOf(unique);
    }

    /** 便捷构造：从任意集合构建。 */
    public static StockPool of(String name, Collection<String> codes) {
        return new StockPool(name, codes == null ? List.of() : List.copyOf(codes));
    }

    public int size() {
        return tsCodes.size();
    }

    public boolean isEmpty() {
        return tsCodes.isEmpty();
    }
}
