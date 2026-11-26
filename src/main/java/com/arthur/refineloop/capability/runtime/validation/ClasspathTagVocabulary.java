package com.arthur.refineloop.capability.runtime.validation;

import com.arthur.refineloop.capability.runtime.CapabilityErrorCode;
import com.arthur.refineloop.common.ApiException;
import com.arthur.refineloop.common.util.JsonUtils;
import com.arthur.refineloop.common.util.Strings;
import org.springframework.core.io.ClassPathResource;
import org.springframework.util.StreamUtils;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * 受控词表的 classpath 实现：读 {@code schema/logic_tags.json}（字符串数组）。
 *
 * <p>为什么随代码冻结而不是进库：标签体系是方法版本的一部分（能力详案 9.1），
 * 改标签等于改方法，必须能和 prompt/Schema 一起被 git 追溯；T15 引入
 * {@code logic_tag_dict} 表后再由该表提供实现（端口不变）。
 *
 * <p>启动期加载一次、失败即抛：词表缺失/为空/重复 = 配置缺陷，宁可起不来也不要静默放行越界标签。
 */
public class ClasspathTagVocabulary implements TagVocabulary {

    /** 默认词表资源路径。 */
    public static final String DEFAULT_RESOURCE = "schema/logic_tags.json";

    private final Set<String> tags;

    public ClasspathTagVocabulary() {
        this(DEFAULT_RESOURCE);
    }

    /**
     * 指定资源路径（测试与多词表场景用）。
     *
     * @param resourcePath classpath 相对路径
     */
    public ClasspathTagVocabulary(String resourcePath) {
        this.tags = load(resourcePath);
    }

    @Override
    public boolean contains(String tag) {
        return tag != null && tags.contains(tag.trim());
    }

    /** 词表快照（不可变，供方法版本快照引用）。 */
    public Set<String> tags() {
        return tags;
    }

    public int size() {
        return tags.size();
    }

    /**
     * 读取并校验词表文件。
     *
     * @param resourcePath classpath 相对路径
     * @return 不可变标签集合
     * @throws ApiException 4005 资源缺失/为空/含空标签/含重复标签/非字符串数组
     */
    static Set<String> load(String resourcePath) {
        String path = Strings.defaultIfBlank(resourcePath, DEFAULT_RESOURCE);
        ClassPathResource resource = new ClassPathResource(path);
        if (!resource.exists()) {
            throw invalid("受控词表文件不存在：" + path);
        }
        String content;
        try (InputStream in = resource.getInputStream()) {
            content = StreamUtils.copyToString(in, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw invalid("受控词表读取失败：" + path + "（" + e.getMessage() + "）");
        }
        List<String> rawTags;
        try {
            rawTags = JsonUtils.parseList(content, String.class);
        } catch (RuntimeException e) {
            throw invalid("受控词表必须是 JSON 字符串数组：" + path + "（" + e.getMessage() + "）");
        }
        if (rawTags == null || rawTags.isEmpty()) {
            throw invalid("受控词表为空：" + path + "（空词表会静默放行所有标签）");
        }
        Set<String> unique = new LinkedHashSet<>();
        Set<String> seen = new HashSet<>();
        for (String raw : rawTags) {
            String tag = Strings.trimToNull(raw);
            if (tag == null) {
                throw invalid("受控词表含空标签：" + path);
            }
            if (!seen.add(tag)) {
                throw invalid("受控词表含重复标签：" + tag + "（" + path + "）");
            }
            unique.add(tag);
        }
        return Set.copyOf(unique);
    }

    private static ApiException invalid(String message) {
        return ApiException.of(CapabilityErrorCode.VALIDATION_CONFIG_INVALID, message);
    }
}
