package com.arthur.refineloop.config;

import com.arthur.refineloop.user.UserViews;
import com.arthur.refineloop.user.model.UserCreateSpec;
import com.arthur.refineloop.user.model.UserRole;
import com.arthur.refineloop.user.service.UserService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

/**
 * 首次启动引导：库里没有任何账号时创建一个管理员，避免「装好却无法登录」。
 *
 * <p>为什么需要它：账号是登录的前提，而空库没有任何途径创建第一个账号；用命令行/接口创建
 * 又要求先登录，形成死锁。引导只在 {@code sys_user} 为空时执行一次，之后完全由账号管理接口维护。
 *
 * <p>安全约定：默认账号/密码是 {@code admin/admin123}，**首次登录后必须立即改密**；生产用
 * {@code REFINE_LOOP_BOOTSTRAP_USERNAME} / {@code REFINE_LOOP_BOOTSTRAP_PASSWORD} 覆盖，
 * 或用 {@code refine-loop.auth.bootstrap-enabled=false} 关闭（此时需自行导入账号）。
 *
 * <p>依赖方向：{@code config → user.service}（装配根允许）。
 */
@Component
@Order(0)
public class UserBootstrapRunner implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(UserBootstrapRunner.class);

    private final AuthProperties authProperties;
    private final UserService userService;

    public UserBootstrapRunner(AuthProperties authProperties, UserService userService) {
        this.authProperties = authProperties;
        this.userService = userService;
    }

    @Override
    public void run(ApplicationArguments args) {
        if (!Boolean.TRUE.equals(authProperties.bootstrapEnabled())) {
            return;
        }
        if (userService.countAll() > 0) {
            return;
        }
        UserViews.UserDetail admin = userService.create(new UserCreateSpec(
                authProperties.bootstrapUsername(),
                authProperties.bootstrapPassword(),
                "系统管理员",
                UserRole.ADMIN.code(),
                "首次启动自动创建，请登录后修改默认密码"));
        log.warn("首次启动已创建管理员账号 username={}（默认密码仅用于首次登录，请立即修改；"
                + "可用 REFINE_LOOP_BOOTSTRAP_PASSWORD 覆盖）", admin.username());
    }
}
