package com.arthur.refineloop.user.service;

import com.arthur.refineloop.common.ApiException;
import com.arthur.refineloop.common.util.Asserts;
import com.arthur.refineloop.common.util.Strings;
import com.arthur.refineloop.data.entity.UserWatchlistEntity;
import com.arthur.refineloop.data.mapper.TushareLookupMapper;
import com.arthur.refineloop.data.mapper.UserWatchlistMapper;
import com.arthur.refineloop.user.UserViews;
import com.arthur.refineloop.user.model.WatchlistSpec;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.regex.Pattern;

/**
 * 用户自选股用例：「不同的用户有不同的股票选择」的承载。
 *
 * <p>依赖方向：{@code data}（{@link UserWatchlistMapper}、{@link TushareLookupMapper}）+ {@code common}；
 * 不依赖 web。
 *
 * <p>不变量：
 * <ul>
 *   <li>所有读写以 {@code userId} 过滤；按 id 访问时若不属于当前用户，返回 404（不暴露他人数据存在性）；</li>
 *   <li>同一用户同一 {@code tsCode} 只能有一行（{@code uk_user_watchlist} 兜底，服务层先给 409）；</li>
 *   <li>{@code tsCode} 归一化为大写并校验 {@code 000001.SZ} 形态；{@code stockName} 是写入时快照。</li>
 * </ul>
 */
@Service
public class UserWatchlistService {

    private static final Pattern TS_CODE_PATTERN = Pattern.compile("^\\d{6}\\.(SZ|SH|BJ)$");
    private static final int MAX_GROUP_LENGTH = 32;
    private static final int MAX_NOTE_LENGTH = 255;

    private final UserWatchlistMapper watchlistMapper;
    private final TushareLookupMapper lookupMapper;
    private final Clock clock;

    public UserWatchlistService(UserWatchlistMapper watchlistMapper, TushareLookupMapper lookupMapper,
                                Clock clock) {
        this.watchlistMapper = watchlistMapper;
        this.lookupMapper = lookupMapper;
        this.clock = clock;
    }

    /** 当前用户的自选股（按排序号、id 升序）。 */
    public UserViews.WatchlistOverview list(long userId) {
        List<UserWatchlistEntity> entities = watchlistMapper.selectList(
                Wrappers.<UserWatchlistEntity>lambdaQuery()
                        .eq(UserWatchlistEntity::getUserId, userId)
                        .orderByAsc(UserWatchlistEntity::getSortNo)
                        .orderByAsc(UserWatchlistEntity::getId));
        List<UserViews.WatchlistItem> items = entities.stream()
                .map(UserWatchlistService::toItem)
                .toList();
        return new UserViews.WatchlistOverview(items, items.size());
    }

    /** 新增自选股；重复标的 → 409。 */
    @Transactional
    public UserViews.WatchlistItem create(long userId, WatchlistSpec spec) {
        String tsCode = normalizeCode(spec.tsCode());
        boolean exists = watchlistMapper.selectCount(Wrappers.<UserWatchlistEntity>lambdaQuery()
                .eq(UserWatchlistEntity::getUserId, userId)
                .eq(UserWatchlistEntity::getTsCode, tsCode)) > 0;
        if (exists) {
            throw ApiException.conflict("该标的已在自选股中: " + tsCode);
        }

        UserWatchlistEntity entity = new UserWatchlistEntity();
        entity.setUserId(userId);
        entity.setTsCode(tsCode);
        entity.setStockName(resolveStockName(tsCode, spec.stockName()));
        entity.setGroupName(normalizeText(spec.groupName(), MAX_GROUP_LENGTH, "groupName"));
        entity.setNote(normalizeText(spec.note(), MAX_NOTE_LENGTH, "note"));
        entity.setSortNo(spec.sortNo() == null ? 0 : spec.sortNo());
        entity.touch(now());
        watchlistMapper.insert(entity);
        return toItem(entity);
    }

    /** 修改自选股（仅备注/分组/排序号/名称快照；标的不可改，换标的请删后新增）。 */
    @Transactional
    public UserViews.WatchlistItem update(long userId, long id, WatchlistSpec spec) {
        UserWatchlistEntity entity = requireOwned(userId, id);
        if (spec.stockName() != null) {
            entity.setStockName(resolveStockName(entity.getTsCode(), spec.stockName()));
        }
        if (spec.groupName() != null) {
            entity.setGroupName(normalizeText(spec.groupName(), MAX_GROUP_LENGTH, "groupName"));
        }
        if (spec.note() != null) {
            entity.setNote(normalizeText(spec.note(), MAX_NOTE_LENGTH, "note"));
        }
        if (spec.sortNo() != null) {
            entity.setSortNo(spec.sortNo());
        }
        entity.touch(now());
        watchlistMapper.updateById(entity);
        return toItem(entity);
    }

    /** 删除自选股；不存在或不属于当前用户 → 404。 */
    @Transactional
    public void delete(long userId, long id) {
        UserWatchlistEntity entity = requireOwned(userId, id);
        watchlistMapper.deleteById(entity.getId());
    }

    private UserWatchlistEntity requireOwned(long userId, long id) {
        UserWatchlistEntity entity = watchlistMapper.selectById(id);
        if (entity == null || !Objects.equals(entity.getUserId(), userId)) {
            throw ApiException.notFound("自选股条目不存在: " + id);
        }
        return entity;
    }

    private static String normalizeCode(String raw) {
        String code = Asserts.notBlank(raw, "tsCode").toUpperCase(Locale.ROOT);
        Asserts.isTrue(TS_CODE_PATTERN.matcher(code).matches(),
                "tsCode 必须形如 000001.SZ（当前 " + raw + "）");
        return code;
    }

    private String resolveStockName(String tsCode, String provided) {
        String name = Strings.trimToNull(provided);
        return name != null ? name : lookupMapper.selectStockName(tsCode);
    }

    private static String normalizeText(String value, int maxLength, String field) {
        String normalized = Strings.trimToNull(value);
        Asserts.maxLength(normalized, maxLength, field);
        return normalized;
    }

    private static UserViews.WatchlistItem toItem(UserWatchlistEntity entity) {
        return new UserViews.WatchlistItem(entity.getId(), entity.getTsCode(), entity.getStockName(),
                entity.getGroupName(), entity.getNote(),
                entity.getSortNo() == null ? 0 : entity.getSortNo(), entity.getCreatedAt());
    }

    private LocalDateTime now() {
        return LocalDateTime.now(clock).truncatedTo(ChronoUnit.SECONDS);
    }
}
