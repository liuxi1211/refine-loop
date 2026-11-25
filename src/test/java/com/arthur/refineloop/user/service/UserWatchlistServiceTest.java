package com.arthur.refineloop.user.service;

import com.arthur.refineloop.common.ApiException;
import com.arthur.refineloop.data.mapper.TushareLookupMapper;
import com.arthur.refineloop.data.mapper.UserWatchlistMapper;
import com.arthur.refineloop.support.MutableClock;
import com.arthur.refineloop.support.SchemaTestSupport;
import com.arthur.refineloop.user.UserViews;
import com.arthur.refineloop.user.model.WatchlistSpec;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowableOfType;

/**
 * 自选股用例：按用户隔离、代码归一化、名称快照、唯一冲突、跨用户 404。
 */
class UserWatchlistServiceTest {

    private static final long ALICE = 1L;
    private static final long BOB = 2L;

    private JdbcTemplate jdbc;
    private UserWatchlistService service;

    @BeforeEach
    void setUp() {
        jdbc = SchemaTestSupport.newJdbcTemplate();
        jdbc.update("INSERT INTO stock_basic (ts_code, name, list_status) VALUES ('000001.SZ', '平安银行', 'L')");
        service = new UserWatchlistService(
                SchemaTestSupport.mapper(jdbc, UserWatchlistMapper.class),
                SchemaTestSupport.mapper(jdbc, TushareLookupMapper.class),
                MutableClock.at("2026-11-24T09:00:00Z"));
    }

    @Test
    @DisplayName("新增：代码归一化大写、名称从 stock_basic 补全、按排序号返回")
    void createNormalizesCodeAndSnapshotsName() {
        UserViews.WatchlistItem created = service.create(ALICE,
                new WatchlistSpec("000001.sz", null, "核心", "长期持有", 1));

        assertThat(created.tsCode()).isEqualTo("000001.SZ");
        assertThat(created.stockName()).isEqualTo("平安银行");
        assertThat(created.groupName()).isEqualTo("核心");
        assertThat(created.sortNo()).isEqualTo(1);

        UserViews.WatchlistOverview overview = service.list(ALICE);
        assertThat(overview.total()).isEqualTo(1);
        assertThat(overview.items().get(0).id()).isEqualTo(created.id());
    }

    @Test
    @DisplayName("同一用户重复标的 → 409")
    void duplicateCodeConflicts() {
        service.create(ALICE, new WatchlistSpec("000001.SZ", "平安银行", null, null, 0));

        assertThat(catchThrowableOfType(
                () -> service.create(ALICE, new WatchlistSpec("000001.SZ", null, null, null, 0)),
                ApiException.class).getCode()).isEqualTo(409);
    }

    @Test
    @DisplayName("跨用户隔离：看不到、改不了、删不掉别人的自选股（404 不暴露存在性）")
    void usersCannotSeeEachOther() {
        UserViews.WatchlistItem aliceItem = service.create(ALICE,
                new WatchlistSpec("000001.SZ", null, null, null, 0));

        assertThat(service.list(BOB).items()).isEmpty();
        assertThat(catchThrowableOfType(
                () -> service.update(BOB, aliceItem.id(), new WatchlistSpec(null, null, "偷看", null, 0)),
                ApiException.class).getCode()).isEqualTo(404);
        assertThat(catchThrowableOfType(
                () -> service.delete(BOB, aliceItem.id()), ApiException.class).getCode()).isEqualTo(404);
        assertThat(service.list(ALICE).total()).isEqualTo(1);
    }

    @Test
    @DisplayName("标的代码格式非法 → 400")
    void invalidCodeIsRejected() {
        assertThat(catchThrowableOfType(
                () -> service.create(ALICE, new WatchlistSpec("000001", null, null, null, 0)),
                ApiException.class).getCode()).isEqualTo(400);
        assertThat(catchThrowableOfType(
                () -> service.create(ALICE, new WatchlistSpec("ABC.SZ", null, null, null, 0)),
                ApiException.class).getCode()).isEqualTo(400);
    }

    @Test
    @DisplayName("更新分组/备注/排序号；标的不可改")
    void updateOnlyEditableFields() {
        UserViews.WatchlistItem created = service.create(ALICE,
                new WatchlistSpec("000001.SZ", "平安银行", "观察", "旧备注", 5));

        UserViews.WatchlistItem updated = service.update(ALICE, created.id(),
                new WatchlistSpec("600000.SH", null, "核心", "新备注", 1));

        assertThat(updated.tsCode()).isEqualTo("000001.SZ");
        assertThat(updated.groupName()).isEqualTo("核心");
        assertThat(updated.note()).isEqualTo("新备注");
        assertThat(updated.sortNo()).isEqualTo(1);
    }

    @Test
    @DisplayName("列表按排序号、id 升序")
    void listIsOrderedBySortNoThenId() {
        service.create(ALICE, new WatchlistSpec("000002.SZ", "万科A", null, null, 2));
        service.create(ALICE, new WatchlistSpec("000001.SZ", "平安银行", null, null, 1));

        assertThat(service.list(ALICE).items()).extracting(UserViews.WatchlistItem::tsCode)
                .containsExactly("000001.SZ", "000002.SZ");
    }
}
