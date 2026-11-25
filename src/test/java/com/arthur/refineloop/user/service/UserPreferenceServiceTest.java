package com.arthur.refineloop.user.service;

import com.arthur.refineloop.common.ApiException;
import com.arthur.refineloop.data.mapper.UserPreferenceMapper;
import com.arthur.refineloop.support.MutableClock;
import com.arthur.refineloop.support.SchemaTestSupport;
import com.arthur.refineloop.user.UserViews;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowableOfType;

/**
 * 交易偏好用例：整组替换语义、键校验、按用户隔离。
 */
class UserPreferenceServiceTest {

    private static final long ALICE = 1L;
    private static final long BOB = 2L;

    private UserPreferenceService service;

    @BeforeEach
    void setUp() {
        JdbcTemplate jdbc = SchemaTestSupport.newJdbcTemplate();
        service = new UserPreferenceService(
                SchemaTestSupport.mapper(jdbc, UserPreferenceMapper.class),
                MutableClock.at("2026-11-24T09:00:00Z"));
    }

    @Test
    @DisplayName("整组替换：提交的键 upsert，未提交的键被删除")
    void replaceIsWholeGroupSemantics() {
        Map<String, String> first = new LinkedHashMap<>();
        first.put("trading.style", "SWING");
        first.put("risk.maxPositionPct", "30");
        service.replace(ALICE, first);

        UserViews.PreferenceView saved = service.replace(ALICE, Map.of("trading.style", "VALUE"));
        assertThat(saved.preferences()).containsExactly(Map.entry("trading.style", "VALUE"));
    }

    @Test
    @DisplayName("空提交清空全部偏好")
    void emptyReplaceClearsAll() {
        service.replace(ALICE, Map.of("trading.style", "SWING"));

        assertThat(service.replace(ALICE, Map.of()).preferences()).isEmpty();
        assertThat(service.list(ALICE).preferences()).isEmpty();
    }

    @Test
    @DisplayName("按用户隔离：不同用户互不可见")
    void preferencesAreIsolatedPerUser() {
        service.replace(ALICE, Map.of("trading.style", "SWING"));
        service.replace(BOB, Map.of("trading.style", "DAY"));

        assertThat(service.list(ALICE).preferences()).containsEntry("trading.style", "SWING");
        assertThat(service.list(BOB).preferences()).containsEntry("trading.style", "DAY");
    }

    @Test
    @DisplayName("偏好键格式非法或超长 → 400")
    void invalidKeyIsRejected() {
        assertThat(catchThrowableOfType(
                () -> service.replace(ALICE, Map.of("1bad", "x")), ApiException.class).getCode())
                .isEqualTo(400);
        assertThat(catchThrowableOfType(
                () -> service.replace(ALICE, Map.of("bad key", "x")), ApiException.class).getCode())
                .isEqualTo(400);
        assertThat(catchThrowableOfType(
                () -> service.replace(ALICE, Map.of("k".repeat(65), "x")), ApiException.class).getCode())
                .isEqualTo(400);
    }

    @Test
    @DisplayName("值超长 → 400")
    void valueTooLongIsRejected() {
        assertThat(catchThrowableOfType(
                () -> service.replace(ALICE, Map.of("trading.style", "x".repeat(1025))),
                ApiException.class).getCode()).isEqualTo(400);
    }
}
