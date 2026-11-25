/**
 * Spring 装配层（组合根）：数据源、MyBatis-Plus、线程池、缓存、OpenAPI、schema 初始化。
 *
 * <p><b>依赖约束</b>：允许依赖所有层（装配本该知道全局）；但不承载业务逻辑，
 * 且只应向其它层暴露 {@code @ConfigurationProperties} 属性类。
 *
 * <p><b>不变量</b>：
 * <ul>
 *   <li>{@code @Configuration} 里不写业务分支、不访问数据库；</li>
 *   <li>配置属性类必须带 {@code @Validated} 与约束，配置写错在启动期暴露。</li>
 * </ul>
 */
package com.arthur.refineloop.config;
