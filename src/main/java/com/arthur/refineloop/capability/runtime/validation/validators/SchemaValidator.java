package com.arthur.refineloop.capability.runtime.validation.validators;

import com.arthur.refineloop.capability.runtime.CapabilityErrorCode;
import com.arthur.refineloop.capability.runtime.validation.JudgmentValidator;
import com.arthur.refineloop.capability.runtime.validation.ValidationContext;
import com.arthur.refineloop.capability.runtime.validation.ValidationError;
import com.arthur.refineloop.common.ApiException;
import com.arthur.refineloop.common.util.Strings;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.networknt.schema.Error;
import com.networknt.schema.Schema;
import com.networknt.schema.SchemaRegistry;
import com.networknt.schema.SpecificationVersion;
import org.springframework.core.io.ClassPathResource;

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * ① JSON Schema 校验（networknt，draft 2020-12）：判断块的字段齐全性、类型与枚举。
 *
 * <p>额外做一次「身份一致性」硬校验：{@code unit_id}/{@code unit_version}/{@code stock_code}/{@code trade_date}
 * 必须与本次执行一致。Schema 无法表达「等于当前单元版本」（同一份 Schema 被该单元所有版本复用），
 * 而一旦判断块混入别的标的或旧版本号，存档即被污染——所以这条必须在代码里堵。
 *
 * <p>为什么这里用 Jackson：networknt 的 API 只接受 Jackson {@code JsonNode}。这是全项目
 * 「业务 JSON 用 fastjson2」的唯一例外，仅限 Schema/资源的读写，不参与业务对象序列化。
 *
 * <p>不变量：Schema 按 id 缓存且只解析一次；资源缺失/非法 → 4005（配置缺陷，不降级）。
 */
public class SchemaValidator implements JudgmentValidator {

    /** 校验器名（进错误与日志）。 */
    public static final String NAME = "schema";

    private static final String SCHEMA_DIR = "schema/";
    private static final int MAX_ACTUAL_LENGTH = 80;

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final SchemaRegistry registry =
            SchemaRegistry.withDefaultDialect(SpecificationVersion.DRAFT_2020_12);
    private final Map<String, Schema> schemaCache = new ConcurrentHashMap<>();

    @Override
    public String name() {
        return NAME;
    }

    @Override
    public List<ValidationError> validate(ValidationContext context) {
        Map<String, Object> block = context.judgmentBlock();
        if (block == null || block.isEmpty()) {
            return List.of(new ValidationError(NAME, "$", "required",
                    "判断块为空：必须在输出末尾给出 ```json 判断块"));
        }
        List<ValidationError> errors = new ArrayList<>(schemaErrors(context));
        errors.addAll(identityErrors(context));
        return errors;
    }

    /** 校验 JSON Schema 结构。 */
    private List<ValidationError> schemaErrors(ValidationContext context) {
        Schema schema = schema(context.schemaId());
        JsonNode node = objectMapper.valueToTree(context.judgmentBlock());
        List<Error> violations = schema.validate(node);
        List<ValidationError> errors = new ArrayList<>(violations.size());
        for (Error violation : violations) {
            errors.add(new ValidationError(NAME, pathOf(violation), violation.getKeyword(),
                    describe(violation)));
        }
        return errors;
    }

    /** 判断块必须属于当前单元 / 当前标的 / 当前交易日。 */
    private List<ValidationError> identityErrors(ValidationContext context) {
        List<ValidationError> errors = new ArrayList<>();
        checkIdentity(errors, "$.unit_id", context.judgmentBlock().get("unit_id"), context.unitId());
        checkIdentity(errors, "$.unit_version", context.judgmentBlock().get("unit_version"),
                context.unitVersion());
        if (context.facts() != null) {
            checkIdentity(errors, "$.stock_code", context.judgmentBlock().get("stock_code"),
                    context.facts().tsCode());
            checkIdentity(errors, "$.trade_date", context.judgmentBlock().get("trade_date"),
                    context.facts().tradeDate());
        }
        return errors;
    }

    private void checkIdentity(List<ValidationError> errors, String path, Object actual, String expected) {
        if (expected == null) {
            return;
        }
        String value = actual == null ? null : String.valueOf(actual).trim();
        if (!expected.equals(value)) {
            errors.add(new ValidationError(NAME, path, "identity_mismatch",
                    "必须为 " + expected + "（当前 " + (value == null ? "缺失" : value) + "）"));
        }
    }

    /**
     * 取（或加载）Schema。
     *
     * @param schemaId Schema 资源名，如 {@code interpret_valuation-v1.json}
     */
    private Schema schema(String schemaId) {
        String key = Strings.trimToNull(schemaId);
        if (key == null) {
            throw invalid("判断 Schema id 不能为空");
        }
        return schemaCache.computeIfAbsent(key, this::loadSchema);
    }

    private Schema loadSchema(String schemaId) {
        String path = SCHEMA_DIR + schemaId;
        ClassPathResource resource = new ClassPathResource(path);
        if (!resource.exists()) {
            throw invalid("判断 Schema 不存在：" + path);
        }
        try (InputStream in = resource.getInputStream()) {
            return registry.getSchema(objectMapper.readTree(in));
        } catch (IOException | RuntimeException e) {
            // 配置缺陷必须 fail-fast；带原因便于定位是哪份 Schema 写坏了
            throw invalid("判断 Schema 读取/编译失败：" + path + "（" + e.getMessage() + "）");
        }
    }

    /**
     * networknt 的实例路径形如 {@code /facts_used/0}，转成 {@code $.facts_used[0]}。
     *
     * <p>特例：{@code required} 违反时实例路径指向「父对象」，缺的字段名在 {@code property} 上——
     * 不补上这一段，回喂文本就会说「$ 缺字段」，模型不知道补哪个。
     */
    private String pathOf(Error error) {
        String path = pathOfLocation(error.getInstanceLocation());
        String property = error.getProperty();
        if ("required".equals(error.getKeyword()) && property != null && !property.isBlank()) {
            return path + "." + property;
        }
        return path;
    }

    private String pathOfLocation(Object location) {
        String raw = location == null ? "" : location.toString();
        if (raw.isBlank() || "/".equals(raw)) {
            return "$";
        }
        StringBuilder path = new StringBuilder("$");
        for (String segment : raw.split("/")) {
            if (segment.isEmpty()) {
                continue;
            }
            if (segment.matches("\\d+")) {
                path.append('[').append(segment).append(']');
            } else {
                path.append('.').append(segment);
            }
        }
        return path.toString();
    }

    /** 自己拼说明，避免依赖消息资源包的语言环境（回喂文本要稳定可断言）。 */
    private String describe(Error error) {
        String keyword = Strings.defaultIfBlank(error.getKeyword(), "unknown");
        JsonNode instance = error.getInstanceNode();
        String actual = instance == null || instance.isNull()
                ? "缺失"
                : Strings.truncate(instance.toString(), MAX_ACTUAL_LENGTH);
        return "违反 " + keyword + " 约束（当前值=" + actual + "）";
    }

    private static ApiException invalid(String message) {
        return ApiException.of(CapabilityErrorCode.VALIDATION_CONFIG_INVALID, message);
    }
}
