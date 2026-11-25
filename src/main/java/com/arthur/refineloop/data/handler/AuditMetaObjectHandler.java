package com.arthur.refineloop.data.handler;

import com.baomidou.mybatisplus.core.handlers.MetaObjectHandler;
import org.apache.ibatis.reflection.MetaObject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;

/**
 * 审计字段自动填充：{@code created_at} / {@code updated_at}。
 *
 * <p>只对声明了 {@code @TableField(fill = ...)} 的字段生效（MP 的 strict 填充语义），
 * 因此不会影响既有实体的手工赋值行为。
 *
 * <p>时间取注入的 {@link Clock}（{@code appClock}），与其他模块的时间源一致；
 * 截断到秒，与 SQLite 文本时间戳口径对齐。
 */
@Component
public class AuditMetaObjectHandler implements MetaObjectHandler {

    private static final Logger log = LoggerFactory.getLogger(AuditMetaObjectHandler.class);

    private final Clock clock;

    public AuditMetaObjectHandler(Clock clock) {
        this.clock = clock;
    }

    @Override
    public void insertFill(MetaObject metaObject) {
        LocalDateTime now = now();
        strictInsertFill(metaObject, "createdAt", LocalDateTime.class, now);
        strictInsertFill(metaObject, "updatedAt", LocalDateTime.class, now);
        if (log.isDebugEnabled()) {
            log.debug("审计字段填充：{} createdAt={}", metaObject.getOriginalObject().getClass().getSimpleName(), now);
        }
    }

    @Override
    public void updateFill(MetaObject metaObject) {
        strictUpdateFill(metaObject, "updatedAt", LocalDateTime.class, now());
    }

    private LocalDateTime now() {
        return LocalDateTime.now(clock).truncatedTo(ChronoUnit.SECONDS);
    }
}
