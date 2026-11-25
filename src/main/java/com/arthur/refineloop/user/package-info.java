/**
 * 账号域：用户账号、登录会话、角色权限、个人交易偏好与自选股。
 *
 * <p>依赖方向：只允许依赖 {@code common}、{@code config}、{@code data}；禁止依赖 {@code web}。
 *
 * <p>不变量：
 * <ul>
 *   <li>{@code sys_user.username} 全库唯一；密码只以 PBKDF2 编码串存储，明文不落库、不进日志；</li>
 *   <li>登录会话只落 SHA-256 哈希，明文令牌只在登录响应出现一次；撤销即失效；</li>
 *   <li>个人数据（{@code user_preference} / {@code user_watchlist}）一律以「当前登录用户 id」过滤，
 *       不信任请求体里的 userId；同一用户偏好键、自选股标的不重复。</li>
 * </ul>
 *
 * <p>权限模型：固定角色（{@link com.arthur.refineloop.user.model.UserRole}）+ 权限码
 * （{@link com.arthur.refineloop.user.model.Permission}），角色→权限映射写在枚举里；
 * 接口用 {@code @RequirePermission} 声明，拦截器统一判定。需要「自定义角色」时再引入
 * {@code sys_role} 表，接口声明不需要改。
 */
package com.arthur.refineloop.user;
