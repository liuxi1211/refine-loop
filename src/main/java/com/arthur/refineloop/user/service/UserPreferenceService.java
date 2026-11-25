package com.arthur.refineloop.user.service;

import com.arthur.refineloop.common.util.Asserts;
import com.arthur.refineloop.data.entity.UserPreferenceEntity;
import com.arthur.refineloop.data.mapper.UserPreferenceMapper;
import com.arthur.refineloop.user.UserViews;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * 用户交易偏好用例：按用户维度的键值配置读写（「交易习惯」的承载）。
 *
 * <p>依赖方向：{@code data}（{@link UserPreferenceMapper}）+ {@code common}；不依赖 web。
 *
 * <p>不变量：
 * <ul>
 *   <li>所有读写都以 {@code userId} 过滤，调用方只能拿到自己的偏好；</li>
 *   <li>{@code pref_key} 必须匹配稳定编码（字母开头，仅含字母/数字/._-，≤64），
 *       {@code pref_value} 为字符串且 ≤1024；复杂结构由调用方以 JSON 文本表达；</li>
 *   <li>{@code replace} 是「整组替换」：未提交的键会被删除，避免前端漏字段导致旧值残留。</li>
 * </ul>
 */
@Service
public class UserPreferenceService {

    private static final Pattern KEY_PATTERN = Pattern.compile("^[a-zA-Z][a-zA-Z0-9._-]{0,63}$");
    private static final int MAX_VALUE_LENGTH = 1024;

    private final UserPreferenceMapper preferenceMapper;
    private final Clock clock;

    public UserPreferenceService(UserPreferenceMapper preferenceMapper, Clock clock) {
        this.preferenceMapper = preferenceMapper;
        this.clock = clock;
    }

    /** 当前用户的全部偏好（按 key 升序，保证前端渲染稳定）。 */
    public UserViews.PreferenceView list(long userId) {
        List<UserPreferenceEntity> entities = preferenceMapper.selectList(
                Wrappers.<UserPreferenceEntity>lambdaQuery()
                        .eq(UserPreferenceEntity::getUserId, userId)
                        .orderByAsc(UserPreferenceEntity::getPrefKey));
        Map<String, String> values = new LinkedHashMap<>();
        for (UserPreferenceEntity entity : entities) {
            values.put(entity.getPrefKey(), entity.getPrefValue());
        }
        return new UserViews.PreferenceView(userId, values);
    }

    /** 整组替换当前用户的偏好（幂等 upsert + 删除未提交键）。 */
    @Transactional
    public UserViews.PreferenceView replace(long userId, Map<String, String> preferences) {
        Map<String, String> normalized = normalize(preferences);
        if (normalized.isEmpty()) {
            preferenceMapper.delete(Wrappers.<UserPreferenceEntity>lambdaQuery()
                    .eq(UserPreferenceEntity::getUserId, userId));
        } else {
            preferenceMapper.delete(Wrappers.<UserPreferenceEntity>lambdaQuery()
                    .eq(UserPreferenceEntity::getUserId, userId)
                    .notIn(UserPreferenceEntity::getPrefKey, normalized.keySet()));
        }
        LocalDateTime now = LocalDateTime.now(clock);
        for (Map.Entry<String, String> entry : normalized.entrySet()) {
            UserPreferenceEntity entity = new UserPreferenceEntity();
            entity.setUserId(userId);
            entity.setPrefKey(entry.getKey());
            entity.setPrefValue(entry.getValue());
            entity.setUpdatedAt(now);
            preferenceMapper.upsert(entity);
        }
        return list(userId);
    }

    private static Map<String, String> normalize(Map<String, String> preferences) {
        Map<String, String> result = new LinkedHashMap<>();
        if (preferences == null || preferences.isEmpty()) {
            return result;
        }
        for (Map.Entry<String, String> entry : preferences.entrySet()) {
            String key = Asserts.notBlank(entry.getKey(), "prefKey");
            Asserts.isTrue(KEY_PATTERN.matcher(key).matches(),
                    "偏好键格式非法（字母开头，仅含字母/数字/._-，≤64 字符）: " + key);
            String value = entry.getValue() == null ? null : entry.getValue().trim();
            Asserts.maxLength(value, MAX_VALUE_LENGTH, "prefValue[" + key + "]");
            result.put(key, value);
        }
        return result;
    }
}
