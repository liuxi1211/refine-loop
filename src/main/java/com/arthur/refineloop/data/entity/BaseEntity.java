package com.arthur.refineloop.data.entity;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.TableField;

import java.time.LocalDateTime;
import java.util.Objects;

/**
 * 业务实体基类：统一审计字段（创建/更新时间）。
 *
 * <p>使用方式（新实体继承即可，字段自动填充由 {@code AuditMetaObjectHandler} 完成）：
 * <pre>{@code
 * @TableName("pipeline_run")
 * public class PipelineRunEntity extends BaseEntity { ... }
 * }</pre>
 *
 * <p>两条约定：
 * <ul>
 *   <li>{@code created_at} 只在插入时写、{@code updated_at} 插入与更新都写；</li>
 *   <li>时间一律 {@code LocalDateTime}（SQLite TEXT 口径），不要用 {@code Date}/{@code Instant}。</li>
 * </ul>
 *
 * <p>已有实体（如 {@code IngestLogEntity}）未继承本类，属历史实现，字段与 schema 一一对应；
 * 新表一律继承，避免每张表重复声明审计字段。
 */
public abstract class BaseEntity {

    @TableField(value = "created_at", fill = FieldFill.INSERT)
    private LocalDateTime createdAt;

    @TableField(value = "updated_at", fill = FieldFill.INSERT_UPDATE)
    private LocalDateTime updatedAt;

    public LocalDateTime getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(LocalDateTime createdAt) {
        this.createdAt = createdAt;
    }

    public LocalDateTime getUpdatedAt() {
        return updatedAt;
    }

    public void setUpdatedAt(LocalDateTime updatedAt) {
        this.updatedAt = updatedAt;
    }

    /** 按主键判等：子类实现 {@link #idOf()} 后即可获得一致的 equals/hashCode 语义。 */
    @Override
    public boolean equals(Object other) {
        if (this == other) {
            return true;
        }
        if (other == null || getClass() != other.getClass()) {
            return false;
        }
        Object selfId = idOf();
        Object otherId = ((BaseEntity) other).idOf();
        return selfId != null && selfId.equals(otherId);
    }

    @Override
    public int hashCode() {
        Object id = idOf();
        return id == null ? System.identityHashCode(this) : Objects.hash(getClass(), id);
    }

    /** 子类返回主键（无主键时返回 null）。 */
    protected Object idOf() {
        return null;
    }

    /**
     * 写库前显式落审计时间：{@code createdAt} 为空才填，{@code updatedAt} 总是刷新。
     *
     * <p>为什么需要它：{@code AuditMetaObjectHandler} 在 Spring 容器里会自动装配，但测试基座
     * （{@code SchemaTestSupport} 手工构建 MyBatis）不一定注册；生产与测试都显式调用可保证
     * 时间口径一致，也避免「生产能跑、测试因 NOT NULL 失败」的假阴性。已填值时 handler 会跳过。
     */
    public void touch(LocalDateTime now) {
        if (now == null) {
            return;
        }
        if (createdAt == null) {
            createdAt = now;
        }
        updatedAt = now;
    }
}
