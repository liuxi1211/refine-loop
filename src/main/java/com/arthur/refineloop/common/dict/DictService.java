package com.arthur.refineloop.common.dict;

import com.arthur.refineloop.common.ApiException;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.config.BeanDefinition;
import org.springframework.context.annotation.ClassPathScanningCandidateComponentProvider;
import org.springframework.core.type.filter.AssignableTypeFilter;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;

/**
 * 字典服务：启动期扫描「实现 {@link DictEnum} 的枚举」+ 汇总 {@link DictProvider} 动态字典，
 * 生成只读快照供前端一次性拉取。
 *
 * <p>为什么在启动期构建：字典是静态映射，运行时不应该有扫描/反射开销；而且<b>重复编码</b>
 * （同一 dictType 下 code 撞车）这类错误必须在启动时炸掉，而不是等前端下拉框出现两个同名项。
 *
 * <p>前端契约：{@code GET /api/common/dicts} → {@code Map<dictType, List<DictItem>>}；
 * 单个字典 {@code GET /api/common/dicts/{type}} → {@code List<DictItem>}。
 * 未知 type 返回 404（拼错要立刻暴露）。
 */
public class DictService {

    private static final Logger log = LoggerFactory.getLogger(DictService.class);

    private final String basePackage;
    private final List<DictProvider> providers;

    /** dictType → 已排序字典项（只读）。 */
    private volatile Map<String, List<DictItem>> snapshot = Map.of();
    /** dictType → (code → item) 索引，用于 labelOf/校验。 */
    private volatile Map<String, Map<String, DictItem>> index = Map.of();

    public DictService(String basePackage, List<DictProvider> providers) {
        this.basePackage = basePackage;
        this.providers = providers == null ? List.of() : List.copyOf(providers);
    }

    @PostConstruct
    public void init() {
        refresh();
    }

    /** 重新扫描枚举 + 重新拉取动态字典（运维接口 / 单测用）。 */
    public synchronized void refresh() {
        // 枚举之间重复编码 = 开发错误 → 启动即失败（scanEnumDicts 内部抛 IllegalStateException）
        Map<String, List<DictItem>> merged = new TreeMap<>(scanEnumDicts());
        for (DictProvider provider : providers) {
            String type = provider.dictType();
            List<DictItem> dynamic = safeItems(provider);
            List<DictItem> existing = merged.get(type);
            // 动态字典重复编码 = 外部数据错误 → 告警并保留已有项，不拖垮整个应用
            merged.put(type, existing == null ? List.copyOf(dynamic) : mergeLenient(type, existing, dynamic));
        }
        Map<String, Map<String, DictItem>> newIndex = new LinkedHashMap<>();
        Map<String, List<DictItem>> immutable = new LinkedHashMap<>();
        merged.forEach((type, items) -> {
            List<DictItem> sorted = items.stream()
                    .sorted(Comparator.comparingInt(DictItem::sort).thenComparing(DictItem::label))
                    .toList();
            immutable.put(type, sorted);
            Map<String, DictItem> byCode = new LinkedHashMap<>();
            for (DictItem item : sorted) {
                byCode.put(item.code(), item);
            }
            newIndex.put(type, Map.copyOf(byCode));
        });
        this.snapshot = Map.copyOf(immutable);
        this.index = Map.copyOf(newIndex);
        log.info("字典已加载：{} 个类型，共 {} 项", snapshot.size(),
                snapshot.values().stream().mapToInt(List::size).sum());
        if (log.isDebugEnabled()) {
            snapshot.forEach((type, items) -> log.debug("字典 {} 共 {} 项：{}", type, items.size(),
                    items.stream().map(DictItem::code).toList()));
        }
    }

    /** 全部字典（前端首屏一次拉取）。 */
    public Map<String, List<DictItem>> all() {
        return snapshot;
    }

    public Set<String> types() {
        return snapshot.keySet();
    }

    /** 单个字典；未知类型 → 404。 */
    public List<DictItem> items(String type) {
        List<DictItem> items = snapshot.get(type);
        if (items == null) {
            throw ApiException.notFound("未知字典类型: " + type + "（可用类型：" + String.join("/", types()) + "）");
        }
        return items;
    }

    /** 单个字典；未知类型返回空列表（供内部代码容错使用）。 */
    public List<DictItem> itemsOrEmpty(String type) {
        return snapshot.getOrDefault(type, List.of());
    }

    /** code → 标签；未知 code 原样返回（展示场景永不抛异常）。 */
    public String labelOf(String type, String code) {
        if (code == null) {
            return null;
        }
        DictItem item = index.getOrDefault(type, Map.of()).get(code);
        return item == null ? code : item.label();
    }

    public Optional<DictItem> find(String type, String code) {
        if (code == null) {
            return Optional.empty();
        }
        return Optional.ofNullable(index.getOrDefault(type, Map.of()).get(code));
    }

    /** 是否为该字典的合法 code（含停用项；停用项只影响新写入策略，由调用方决定）。 */
    public boolean contains(String type, String code) {
        return find(type, code).isPresent();
    }

    /** 合法且启用。 */
    public boolean isEnabledCode(String type, String code) {
        return find(type, code).map(DictItem::enabled).orElse(false);
    }

    /* ---------------- 内部实现 ---------------- */

    private Map<String, List<DictItem>> scanEnumDicts() {
        Map<String, List<DictItem>> result = new TreeMap<>();
        ClassPathScanningCandidateComponentProvider scanner =
                new ClassPathScanningCandidateComponentProvider(false);
        scanner.addIncludeFilter(new AssignableTypeFilter(DictEnum.class));
        Set<String> classNames = new LinkedHashSet<>();
        for (BeanDefinition definition : scanner.findCandidateComponents(basePackage)) {
            classNames.add(definition.getBeanClassName());
        }
        for (String className : classNames) {
            Class<?> candidate;
            try {
                candidate = Class.forName(className, false, Thread.currentThread().getContextClassLoader());
            } catch (ClassNotFoundException | LinkageError e) {
                log.warn("字典扫描跳过无法加载的类 {}：{}", className, e.toString());
                continue;
            }
            if (!candidate.isEnum() || !DictEnum.class.isAssignableFrom(candidate)) {
                continue;
            }
            Object[] constants = candidate.getEnumConstants();
            if (constants == null || constants.length == 0) {
                continue;
            }
            List<DictItem> items = new ArrayList<>(constants.length);
            for (Object constant : constants) {
                items.add(DictItem.of((DictEnum) constant));
            }
            String type = ((DictEnum) constants[0]).dictType();
            result.merge(type, items, (left, right) -> merge(type, left, right));
            log.debug("字典枚举登记：{} ← {}（{} 项）", type, className, items.size());
        }
        return result;
    }

    private static List<DictItem> merge(String type, List<DictItem> left, List<DictItem> right) {
        Map<String, DictItem> byCode = new LinkedHashMap<>();
        for (DictItem item : left) {
            register(type, byCode, item);
        }
        for (DictItem item : right) {
            register(type, byCode, item);
        }
        return new ArrayList<>(byCode.values());
    }

    /** 动态字典合并：重复编码只告警并保留先到者（外部数据问题不应导致启动失败）。 */
    private List<DictItem> mergeLenient(String type, List<DictItem> left, List<DictItem> right) {
        Map<String, DictItem> byCode = new LinkedHashMap<>();
        for (DictItem item : left) {
            byCode.put(item.code(), item);
        }
        for (DictItem item : right) {
            DictItem previous = byCode.putIfAbsent(item.code(), item);
            if (previous != null) {
                log.warn("动态字典 {} 的编码 {} 与已有项重复（保留 label={}，忽略 label={}），请检查数据源",
                        type, item.code(), previous.label(), item.label());
            }
        }
        return new ArrayList<>(byCode.values());
    }

    private static void register(String type, Map<String, DictItem> byCode, DictItem item) {
        DictItem previous = byCode.putIfAbsent(item.code(), item);
        if (previous != null) {
            throw new IllegalStateException("字典 " + type + " 存在重复编码 " + item.code()
                    + "：label=[" + previous.label() + "] 与 [" + item.label() + "] 冲突，请修正枚举定义");
        }
    }

    private List<DictItem> safeItems(DictProvider provider) {
        try {
            List<DictItem> items = provider.items();
            return items == null ? List.of() : items;
        } catch (RuntimeException e) {
            log.warn("动态字典 {} 加载失败，本次下发空列表：{}", provider.dictType(), e.toString());
            return List.of();
        }
    }
}
