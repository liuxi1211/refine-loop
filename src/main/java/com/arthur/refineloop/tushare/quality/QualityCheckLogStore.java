package com.arthur.refineloop.tushare.quality;

import com.arthur.refineloop.common.page.PageQuery;
import com.arthur.refineloop.data.entity.QualityCheckLogEntity;
import com.arthur.refineloop.data.mapper.QualityCheckLogMapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;

/**
 * quality_check_log 读写（S0 检查结果留痕 + 历史查询），数据访问走 MyBatis-Plus Mapper。
 *
 * <p>表结构（契约 §4.5）：{@code (id, check_time, trigger_type, result, items JSON, duration_ms)}，
 * 没有 trade_date 列，因此 {@code items} JSON 落为 {@code {"tradeDate":"yyyyMMdd","items":[...]}}；
 * 读取时同时兼容历史/外部写入的纯数组格式（tradeDate 为 null）。
 */
@Component
public class QualityCheckLogStore {

    private static final Logger log = LoggerFactory.getLogger(QualityCheckLogStore.class);

    private final QualityCheckLogMapper mapper;
    private final ObjectMapper objectMapper;

    public QualityCheckLogStore(QualityCheckLogMapper mapper, ObjectMapper objectMapper) {
        this.mapper = mapper;
        this.objectMapper = objectMapper;
    }

    /** 落库一条检查结果，返回主键 id（库不可用时向上抛，由全局异常处理转 503）。 */
    public long save(LocalDateTime checkTime, String tradeDate, String triggerType, String result,
                     long durationMs, List<QualityCheckItem> items) {
        QualityCheckLogEntity entity = new QualityCheckLogEntity();
        entity.setCheckTime(checkTime);
        entity.setTriggerType(triggerType);
        entity.setResult(result);
        entity.setItems(toJson(tradeDate, items));
        entity.setDurationMs(durationMs);
        mapper.insert(entity);
        return entity.getId() == null ? -1L : entity.getId();
    }

    /** 最近一次检查（GET /api/ops/quality/latest）；无记录返回 empty。 */
    public Optional<QualityReport> latest() {
        List<QualityCheckLogEntity> rows = mapper.selectList(Wrappers.<QualityCheckLogEntity>lambdaQuery()
                .orderByDesc(QualityCheckLogEntity::getId)
                .last("LIMIT 1"));
        return rows.stream().findFirst().map(this::toReport);
    }

    /** 近 {@code days} 天检查条数（GET /api/ops/quality/history 的 total）。 */
    public long countSince(LocalDateTime since) {
        return mapper.selectCount(Wrappers.<QualityCheckLogEntity>lambdaQuery()
                .ge(QualityCheckLogEntity::getCheckTime, since));
    }

    /** 近 {@code days} 天检查分页（按 id 倒序 = 最近优先）。 */
    public List<QualityReport> pageSince(LocalDateTime since, PageQuery query) {
        List<QualityCheckLogEntity> rows = mapper.selectList(Wrappers.<QualityCheckLogEntity>lambdaQuery()
                .ge(QualityCheckLogEntity::getCheckTime, since)
                .orderByDesc(QualityCheckLogEntity::getId)
                .last("LIMIT " + query.limit() + " OFFSET " + query.offset()));
        return rows.stream().map(this::toReport).toList();
    }

    // ------------------------------------------------------------------ JSON

    private String toJson(String tradeDate, List<QualityCheckItem> items) {
        try {
            return objectMapper.writeValueAsString(new StoredItems(tradeDate, items == null ? List.of() : items));
        } catch (Exception e) {
            throw new IllegalStateException("质量门检查项序列化失败: " + e.getMessage(), e);
        }
    }

    private QualityReport toReport(QualityCheckLogEntity entity) {
        ParsedItems parsed = parse(entity.getItems());
        return new QualityReport(
                entity.getId() == null ? 0L : entity.getId(),
                entity.getCheckTime(),
                parsed.tradeDate(),
                entity.getTriggerType(),
                entity.getResult(),
                entity.getDurationMs() == null ? 0L : entity.getDurationMs(),
                parsed.items());
    }

    private ParsedItems parse(String json) {
        if (json == null || json.isBlank()) {
            return new ParsedItems(null, List.of());
        }
        try {
            JsonNode root = objectMapper.readTree(json);
            // SQLite 的 JSON 语义列实为 TEXT：原样返回 JSON 文本；此处二次解析仅用于兜底
            // 「被额外引号包裹/历史库中 double-encoded」的极端情况。
            if (root.isTextual()) {
                root = objectMapper.readTree(root.asText());
            }
            String tradeDate = null;
            JsonNode itemsNode = root;
            if (root.isObject()) {
                tradeDate = root.hasNonNull("tradeDate") ? root.get("tradeDate").asText() : null;
                itemsNode = root.get("items");
            }
            List<QualityCheckItem> items = itemsNode != null && itemsNode.isArray()
                    ? Arrays.asList(objectMapper.treeToValue(itemsNode, QualityCheckItem[].class))
                    : List.of();
            return new ParsedItems(tradeDate, items);
        } catch (Exception e) {
            // 脏数据不阻塞历史列表：记 warning 并按空明细返回
            log.warn("quality_check_log.items 解析失败（按空明细处理）: {}", e.getMessage());
            return new ParsedItems(null, List.of());
        }
    }

    private record StoredItems(String tradeDate, List<QualityCheckItem> items) {
    }

    private record ParsedItems(String tradeDate, List<QualityCheckItem> items) {
    }
}
