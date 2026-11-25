package com.arthur.refineloop.config;

import com.baomidou.mybatisplus.annotation.DbType;
import com.baomidou.mybatisplus.extension.plugins.MybatisPlusInterceptor;
import com.baomidou.mybatisplus.extension.plugins.inner.PaginationInnerInterceptor;
import org.mybatis.spring.annotation.MapperScan;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * MyBatis-Plus 配置入口。
 *
 * <p>数据访问分层（ORM 改造后）：
 * <ul>
 *   <li>自有业务表（ingest_log / sys_config / rate_limit_counter / quality_check_log）：
 *       {@code BaseMapper} 实体 + {@code LambdaQueryWrapper}；upsert 用注解 SQL 的
 *       {@code ON CONFLICT ... DO UPDATE}（SQLite）；</li>
 *   <li>41 张 Tushare 镜像表：{@code TushareTableMapper} 动态 SQL Provider（元数据驱动，
 *       不写 41 份 XML），批量写走 {@code MyBatisBatchExecutor}（BATCH 执行器）；</li>
 *   <li>固定业务查询（交易日历/股票清单/质量门聚合/今日统计）：类型化 Mapper 注解 SQL，可编译期审阅。</li>
 * </ul>
 *
 * <p>分页：{@link PaginationInnerInterceptor} 必须注册，否则 {@code IPage} 查询不会生成
 * LIMIT/OFFSET（静默返回全表）。分页参数统一用 {@code common.page.PageQuery}，
 * 结果转换用 {@code common.page.PageResults}。
 *
 * <p>全局配置（下划线转驼峰、{@code jdbcTypeForNull=NULL}、时间类型处理器）在
 * {@code application.yml} 的 {@code mybatis-plus.*} 中声明。
 */
@Configuration
@MapperScan("com.arthur.refineloop.data.mapper")
public class MyBatisPlusConfig {

    @Bean
    public MybatisPlusInterceptor mybatisPlusInterceptor() {
        MybatisPlusInterceptor interceptor = new MybatisPlusInterceptor();
        // SQLite 方言：单写者 + WAL，分页用 LIMIT/OFFSET
        PaginationInnerInterceptor pagination = new PaginationInnerInterceptor(DbType.SQLITE);
        // 单页上限，防止前端传超大 size 把内存打爆（与 PageQuery.MAX_SIZE 一致）
        pagination.setMaxLimit(100L);
        interceptor.addInnerInterceptor(pagination);
        return interceptor;
    }
}
