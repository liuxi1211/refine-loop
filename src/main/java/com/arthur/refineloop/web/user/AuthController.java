package com.arthur.refineloop.web.user;

import com.arthur.refineloop.common.Result;
import com.arthur.refineloop.user.UserViews;
import com.arthur.refineloop.user.model.AuthenticatedUser;
import com.arthur.refineloop.user.model.Permission;
import com.arthur.refineloop.user.service.AuthService;
import com.arthur.refineloop.user.service.UserService;
import com.arthur.refineloop.web.auth.BearerTokens;
import com.arthur.refineloop.web.auth.CurrentUserContext;
import jakarta.validation.Valid;
import org.springframework.http.HttpHeaders;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 认证端点（{@code /api/auth/*}）：登录、登出、当前用户、自助改密。
 *
 * <p>登录是唯一无需令牌的写端点（拦截器白名单）；其余端点由拦截器保证已认证。
 * 控制器只做参数接收与转发，密码校验/令牌签发全部在 {@link AuthService}。
 */
@RestController
@RequestMapping("/auth")
public class AuthController {

    private final AuthService authService;
    private final UserService userService;

    public AuthController(AuthService authService, UserService userService) {
        this.authService = authService;
        this.userService = userService;
    }

    /** POST /api/auth/login：登录并签发令牌（用户名或密码错误 → 401，账号停用 → 403）。 */
    @PostMapping("/login")
    public Result<UserViews.LoginResult> login(@Valid @RequestBody UserRequests.LoginRequest request) {
        return Result.ok(authService.login(request.username(), request.password()));
    }

    /** POST /api/auth/logout：撤销当前令牌（幂等）。 */
    @PostMapping("/logout")
    public Result<Void> logout(
            @RequestHeader(name = HttpHeaders.AUTHORIZATION, required = false) String authorization) {
        authService.logout(BearerTokens.extract(authorization));
        return Result.ok();
    }

    /** GET /api/auth/me：当前登录用户与权限码（前端据此渲染菜单/按钮）。 */
    @GetMapping("/me")
    public Result<UserViews.CurrentUser> me() {
        AuthenticatedUser user = CurrentUserContext.require();
        return Result.ok(new UserViews.CurrentUser(user.id(), user.username(), user.displayName(),
                user.role().code(), user.roleLabel(),
                user.permissions().stream().map(Permission::code).sorted().toList()));
    }

    /** POST /api/auth/password：自助改密（校验原密码，成功后撤销全部会话）。 */
    @PostMapping("/password")
    public Result<Void> changePassword(@Valid @RequestBody UserRequests.ChangePasswordRequest request) {
        userService.changePassword(CurrentUserContext.require().id(),
                request.oldPassword(), request.newPassword());
        return Result.ok();
    }
}
