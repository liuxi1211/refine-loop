package com.arthur.refineloop.capability.runtime;

import com.arthur.refineloop.capability.spi.CapabilityUnit;
import com.arthur.refineloop.capability.spi.UnitScope;
import com.arthur.refineloop.capability.spi.UnitType;
import com.arthur.refineloop.common.ApiException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.util.ClassUtils;

import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Pattern;

/**
 * 能力单元注册表：启动期扫描 {@code @CapabilityUnit} Bean，校验并建立 unitId → 实例索引。
 *
 * <p>纯类（非 Spring 组件），由 {@code config.CapabilityConfig} 装配、{@code UnitRegistryInitializer}
 * 在启动期喂入扫描结果，便于单测直接构造。
 */
@Slf4j
public class UnitRegistry {

    private static final Class<com.arthur.refineloop.capability.spi.annotation.CapabilityUnit> ANNOTATION_TYPE =
            com.arthur.refineloop.capability.spi.annotation.CapabilityUnit.class;
    private static final Pattern UNIT_ID = Pattern.compile("^[a-z][a-z0-9_]{2,39}$");
    private static final Pattern VERSION = Pattern.compile("^v\\d+(\\.\\d+)?$");

    private final Map<String, Entry> entries = new ConcurrentHashMap<>();

    /**
     * 注册一个能力单元 Bean。
     *
     * @param bean Spring 容器中的实例（可能是 CGLIB 代理）
     * @return 注册条目
     * @throws ApiException 4002 定义不合法 / 4001 重复注册
     */
    public Entry register(Object bean) {
        if (!(bean instanceof CapabilityUnit unit)) {
            throw ApiException.of(CapabilityErrorCode.INVALID_UNIT,
                    "Bean 未实现 CapabilityUnit：" + (bean == null ? "null" : bean.getClass().getName()));
        }
        Class<?> unitClass = ClassUtils.getUserClass(bean);
        com.arthur.refineloop.capability.spi.annotation.CapabilityUnit meta =
                unitClass.getAnnotation(ANNOTATION_TYPE);
        if (meta == null) {
            throw ApiException.of(CapabilityErrorCode.INVALID_UNIT,
                    "缺少 @CapabilityUnit 注解：" + unitClass.getName());
        }
        validate(unitClass, meta);

        Entry entry = new Entry(meta.id(), meta.version(), meta.type(), meta.scope(), meta.modelProfile(), unit);
        Entry previous = entries.putIfAbsent(meta.id(), entry);
        if (previous != null) {
            throw ApiException.of(CapabilityErrorCode.DUPLICATE_UNIT,
                    "unitId 重复：" + meta.id() + "（" + unitClass.getName() + "）");
        }
        log.info("[capability] 注册单元 {} {} type={} scope={}",
                meta.id(), meta.version(), meta.type(), meta.scope());
        return entry;
    }

    private void validate(Class<?> unitClass, com.arthur.refineloop.capability.spi.annotation.CapabilityUnit meta) {
        if (!UNIT_ID.matcher(meta.id()).matches()) {
            throw ApiException.of(CapabilityErrorCode.INVALID_UNIT,
                    "unitId 非法（需小写字母开头、仅小写/数字/下划线、3-40 位）：" + meta.id());
        }
        if (!VERSION.matcher(meta.version()).matches()) {
            throw ApiException.of(CapabilityErrorCode.INVALID_UNIT,
                    "version 非法（需形如 v1 / v1.0）：" + meta.version());
        }
        if (meta.type() == UnitType.INTERPRET && meta.scope() != UnitScope.PER_STOCK) {
            throw ApiException.of(CapabilityErrorCode.INVALID_UNIT,
                    "INTERPRET 单元必须为 PER_STOCK：" + meta.id());
        }
        if (meta.modelProfile() == null || meta.modelProfile().isBlank()) {
            throw ApiException.of(CapabilityErrorCode.INVALID_UNIT,
                    "modelProfile 不能为空：" + unitClass.getName());
        }
    }

    /** 按 unitId 查找；不存在返回 null。 */
    public Entry find(String unitId) {
        return entries.get(unitId);
    }

    /** 按 unitId 查找；不存在抛 4004。 */
    public Entry require(String unitId) {
        Entry entry = entries.get(unitId);
        if (entry == null) {
            throw ApiException.of(CapabilityErrorCode.UNIT_NOT_FOUND, "能力单元未注册：" + unitId);
        }
        return entry;
    }

    /** 全部已注册单元（不可变快照）。 */
    public Collection<Entry> all() {
        return List.copyOf(entries.values());
    }

    public int size() {
        return entries.size();
    }

    /**
     * 注册条目：元数据来自注解，instance 是 Spring Bean。
     */
    public record Entry(
            String unitId,
            String version,
            UnitType type,
            UnitScope scope,
            String modelProfile,
            CapabilityUnit instance) {
    }
}
