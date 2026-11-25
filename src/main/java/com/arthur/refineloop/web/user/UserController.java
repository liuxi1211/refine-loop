package com.arthur.refineloop.web.user;

import com.arthur.refineloop.common.PageResult;
import com.arthur.refineloop.common.Result;
import com.arthur.refineloop.common.page.PageQuery;
import com.arthur.refineloop.user.UserViews;
import com.arthur.refineloop.user.model.Permission;
import com.arthur.refineloop.user.service.UserService;
import com.arthur.refineloop.web.auth.CurrentUserContext;
import com.arthur.refineloop.web.auth.RequirePermission;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 用户管理端点（{@code /api/users*}，管理员专用）。
 *
 * <p>权限：全部端点要求 {@code user:read}/{@code user:write}，仅 {@code ADMIN} 角色拥有。
 * 业务规则（用户名唯一、至少保留一个管理员、密码策略）在 {@link UserService}。
 */
@RestController
@RequestMapping("/users")
public class UserController {

    private final UserService userService;

    public UserController(UserService userService) {
        this.userService = userService;
    }

    /** GET /api/users：分页查询（keyword 匹配用户名/显示名，role/active 可选过滤）。 */
    @GetMapping
    @RequirePermission(Permission.USER_READ)
    public Result<PageResult<UserViews.UserSummary>> page(
            @RequestParam(required = false) String keyword,
            @RequestParam(required = false) String role,
            @RequestParam(required = false) Boolean active,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int size) {
        return Result.ok(userService.page(keyword, role, active, PageQuery.of(page, size)));
    }

    /** GET /api/users/{id}：用户详情（不存在 → 404）。 */
    @GetMapping("/{id}")
    @RequirePermission(Permission.USER_READ)
    public Result<UserViews.UserDetail> detail(@PathVariable long id) {
        return Result.ok(userService.get(id));
    }

    /** POST /api/users：新建用户（用户名重复 → 409，角色非法 → 400）。 */
    @PostMapping
    @RequirePermission(Permission.USER_WRITE)
    public Result<UserViews.UserDetail> create(@Valid @RequestBody UserRequests.CreateUserRequest request) {
        return Result.ok(userService.create(request.toSpec()));
    }

    /** PUT /api/users/{id}：修改显示名/角色/启停（降级最后一个管理员 → 1004）。 */
    @PutMapping("/{id}")
    @RequirePermission(Permission.USER_WRITE)
    public Result<UserViews.UserDetail> update(@PathVariable long id,
                                               @Valid @RequestBody UserRequests.UpdateUserRequest request) {
        return Result.ok(userService.update(id, request.toSpec()));
    }

    /** POST /api/users/{id}/password：管理员重置密码（撤销该用户全部会话）。 */
    @PostMapping("/{id}/password")
    @RequirePermission(Permission.USER_WRITE)
    public Result<Void> resetPassword(@PathVariable long id,
                                      @Valid @RequestBody UserRequests.ResetPasswordRequest request) {
        userService.resetPassword(id, request.password());
        return Result.ok();
    }

    /** DELETE /api/users/{id}：删除用户（不能删自己/最后一个管理员）。 */
    @DeleteMapping("/{id}")
    @RequirePermission(Permission.USER_WRITE)
    public Result<Void> delete(@PathVariable long id) {
        userService.delete(id, CurrentUserContext.require().id());
        return Result.ok();
    }
}
