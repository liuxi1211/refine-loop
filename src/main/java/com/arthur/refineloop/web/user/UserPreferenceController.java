package com.arthur.refineloop.web.user;

import com.arthur.refineloop.common.Result;
import com.arthur.refineloop.user.UserViews;
import com.arthur.refineloop.user.service.UserPreferenceService;
import com.arthur.refineloop.web.auth.CurrentUserContext;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * 我的交易偏好（{@code /api/users/me/preferences}）。
 *
 * <p>不标注 {@code @RequirePermission}：任何已登录用户都可以读写**自己的**偏好；
 * 用户 id 来自登录态（{@link CurrentUserContext}），不接受请求体指定。
 */
@RestController
@RequestMapping("/users/me/preferences")
public class UserPreferenceController {

    private final UserPreferenceService preferenceService;

    public UserPreferenceController(UserPreferenceService preferenceService) {
        this.preferenceService = preferenceService;
    }

    /** GET /api/users/me/preferences：当前用户全部偏好。 */
    @GetMapping
    public Result<UserViews.PreferenceView> list() {
        return Result.ok(preferenceService.list(CurrentUserContext.require().id()));
    }

    /** PUT /api/users/me/preferences：整组替换（未提交的键会被删除）。 */
    @PutMapping
    public Result<UserViews.PreferenceView> replace(
            @RequestBody(required = false) UserRequests.PreferencesRequest request) {
        Map<String, String> preferences = request == null ? Map.of() : request.preferences();
        return Result.ok(preferenceService.replace(CurrentUserContext.require().id(), preferences));
    }
}
