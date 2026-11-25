package com.arthur.refineloop.data.batch;

import com.arthur.refineloop.config.IngestProperties;
import org.apache.ibatis.session.ExecutorType;
import org.apache.ibatis.session.SqlSessionFactory;
import org.mybatis.spring.SqlSessionTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.List;
import java.util.function.BiConsumer;
import java.util.function.Consumer;

/**
 * MyBatis 批量执行器：把「41 张镜像表的批量 upsert / 批量插入」跑在 MyBatis 的 BATCH 执行器上。
 *
 * <p>为什么不用 JdbcTemplate.batchUpdate：ORM 改造后所有落库统一走 MyBatis Mapper
 * （复用 {@code MappedStatement}、参数映射、类型处理器与事务管理），本类只负责
 * 「BATCH 执行器 + 事务边界 + 批大小控制」这三件与业务无关的事。
 *
 * <p>实现要点：
 * <ul>
 *   <li>用 {@link SqlSessionTemplate}（{@link ExecutorType#BATCH}）执行 Mapper 方法：
 *       Spring 事务同步会在提交前 flush 语句，因此批量写与事务语义天然一致；</li>
 *   <li>每 {@code batchSize} 行显式 {@code flushStatements()} 一次（默认 500 行/批，《手册》3.3）；</li>
 *   <li>整个批次包在 {@link TransactionTemplate} 内：无外部事务时自成短事务，有外部事务时加入
 *       （例如「整块先删后插」要求删除与插入原子提交）。</li>
 * </ul>
 */
@Component
public class MyBatisBatchExecutor {

    private final SqlSessionTemplate batchTemplate;
    private final TransactionTemplate transactionTemplate;
    private final int batchSize;

    public MyBatisBatchExecutor(SqlSessionFactory sqlSessionFactory,
                                PlatformTransactionManager transactionManager,
                                IngestProperties ingestProperties) {
        this.batchTemplate = new SqlSessionTemplate(sqlSessionFactory, ExecutorType.BATCH);
        this.transactionTemplate = new TransactionTemplate(transactionManager);
        this.batchSize = ingestProperties.getBatchSize() > 0 ? ingestProperties.getBatchSize() : 500;
    }

    /**
     * 在事务内用 BATCH 执行器逐行执行 {@code operation}，每 {@code batchSize} 行 flush 一次。
     *
     * @return 实际交给执行器的行数（= 成功排入批次的行数）
     */
    public <M, T> int execute(Class<M> mapperType, List<T> rows, BiConsumer<M, T> operation) {
        if (rows == null || rows.isEmpty()) {
            return 0;
        }
        Integer executed = transactionTemplate.execute(status -> {
            M mapper = batchTemplate.getMapper(mapperType);
            int count = 0;
            for (T row : rows) {
                operation.accept(mapper, row);
                count++;
                if (count % batchSize == 0) {
                    batchTemplate.flushStatements();
                }
            }
            batchTemplate.flushStatements();
            return count;
        });
        return executed == null ? 0 : executed;
    }

    /**
     * 在事务内用 BATCH 执行器执行一段 Mapper 操作（先删后插等需要「语句顺序 + 原子提交」的场景）。
     *
     * <p>注意：必须用这里传入的 Mapper（BATCH 会话）执行所有语句；若在同一事务里混用普通
     * {@code SqlSessionTemplate} 的 Mapper，MyBatis-Spring 会因 ExecutorType 不一致报错。
     */
    public <M> void executeInTransaction(Class<M> mapperType, Consumer<M> action) {
        transactionTemplate.executeWithoutResult(status -> {
            M mapper = batchTemplate.getMapper(mapperType);
            action.accept(mapper);
            batchTemplate.flushStatements();
        });
    }

    /** 批大小（供日志/断言）。 */
    public int batchSize() {
        return batchSize;
    }
}
