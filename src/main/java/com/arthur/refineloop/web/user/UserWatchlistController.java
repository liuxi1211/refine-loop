package com.arthur.refineloop.web.user;

import com.arthur.refineloop.common.Result;
import com.arthur.refineloop.user.UserViews;
import com.arthur.refineloop.user.service.UserWatchlistService;
import com.arthur.refineloop.web.auth.CurrentUserContext;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 我的自选股（{@code /api/users/me/watchlist}）。
 *
 * <p>不标注 {@code @RequirePermission}：任何已登录用户都可以管理**自己的**自选股；
 * 用户 id 来自登录态，跨用户访问一律 404（不暴露他人数据存在性）。
 */
@RestController
@RequestMapping("/users/me/watchlist")
public class UserWatchlistController {

    private final UserWatchlistService watchlistService;

    public UserWatchlistController(UserWatchlistService watchlistService) {
        this.watchlistService = watchlistService;
    }

    /** GET /api/users/me/watchlist：当前用户自选股（按排序号升序）。 */
    @GetMapping
    public Result<UserViews.WatchlistOverview> list() {
        return Result.ok(watchlistService.list(CurrentUserContext.require().id()));
    }

    /** POST /api/users/me/watchlist：新增自选股（重复标的 → 409）。 */
    @PostMapping
    public Result<UserViews.WatchlistItem> create(@Valid @RequestBody UserRequests.WatchlistRequest request) {
        return Result.ok(watchlistService.create(CurrentUserContext.require().id(), request.toSpec()));
    }

    /** PUT /api/users/me/watchlist/{id}：修改分组/备注/排序号（不存在或非本人 → 404）。 */
    @PutMapping("/{id}")
    public Result<UserViews.WatchlistItem> update(@PathVariable long id,
                                                  @Valid @RequestBody UserRequests.WatchlistUpdateRequest request) {
        return Result.ok(watchlistService.update(CurrentUserContext.require().id(), id, request.toSpec()));
    }

    /** DELETE /api/users/me/watchlist/{id}：删除自选股（不存在或非本人 → 404）。 */
    @DeleteMapping("/{id}")
    public Result<Void> delete(@PathVariable long id) {
        watchlistService.delete(CurrentUserContext.require().id(), id);
        return Result.ok();
    }
}
