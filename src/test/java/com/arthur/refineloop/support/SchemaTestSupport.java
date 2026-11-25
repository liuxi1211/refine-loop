package com.arthur.refineloop.support;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import javax.sql.DataSource;

import com.arthur.refineloop.config.IngestProperties;
import com.arthur.refineloop.data.batch.MyBatisBatchExecutor;
import com.arthur.refineloop.data.handler.AuditMetaObjectHandler;
import com.arthur.refineloop.data.handler.SqliteLocalDateTimeTypeHandler;
import com.arthur.refineloop.data.mapper.IngestLogMapper;
import com.arthur.refineloop.data.mapper.OpsStatsMapper;
import com.arthur.refineloop.data.mapper.QualityCheckLogMapper;
import com.arthur.refineloop.data.mapper.QualityQueryMapper;
import com.arthur.refineloop.data.mapper.RateLimitCounterMapper;
import com.arthur.refineloop.data.mapper.SysConfigMapper;
import com.arthur.refineloop.data.mapper.SysUserMapper;
import com.arthur.refineloop.data.mapper.SysUserSessionMapper;
import com.arthur.refineloop.data.mapper.TushareLookupMapper;
import com.arthur.refineloop.data.mapper.TushareTableMapper;
import com.arthur.refineloop.data.mapper.UserPreferenceMapper;
import com.arthur.refineloop.data.mapper.UserWatchlistMapper;
import com.baomidou.mybatisplus.annotation.DbType;
import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.config.GlobalConfig;
import com.baomidou.mybatisplus.core.toolkit.GlobalConfigUtils;
import com.baomidou.mybatisplus.extension.plugins.MybatisPlusInterceptor;
import com.baomidou.mybatisplus.extension.plugins.inner.PaginationInnerInterceptor;
import com.baomidou.mybatisplus.extension.spring.MybatisSqlSessionFactoryBean;
import org.apache.ibatis.session.SqlSessionFactory;
import org.apache.ibatis.type.JdbcType;
import org.mybatis.spring.SqlSessionTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Clock;

/**
 * SQLite 测试基座：读取工程根目录 {@code db/schema.sql}，在临时 SQLite 库上全量执行；
 * 并负责构建测试用的 MyBatis（MyBatis-Plus）会话工厂 / Mapper / 批量执行器。
 *
 * <p>数据库引擎已由 MySQL 迁移为 SQLite（嵌入式单文件库），因此<b>单元测试与生产使用同一引擎</b>，
 * 不再需要 MySQL→H2 的 SQL 兼容变换：schema 是 SQLite 原生方言，测试直接执行。
 *
 * <p>约定：
 * <ul>
 *   <li>{@link #newDataSource()}：在 {@code target/test-db/} 下新建随机名库文件，
 *       URL 启用 {@code journal_mode=WAL}、{@code busy_timeout=5000}、
 *       {@code date_class=TEXT}（时间戳以 {@code yyyy-MM-dd HH:mm:ss} 文本存储，与 DEFAULT CURRENT_TIMESTAMP 一致）；</li>
 *   <li>{@link #newJdbcTemplate()}：新建库 + 执行 {@code db/schema.sql}（测试夹具/断言用）；</li>
 *   <li>{@link #reset(JdbcTemplate)}：删除全部表后重新执行 schema（每个测试方法独立初始状态）；</li>
 *   <li>{@link #mapper(JdbcTemplate, Class)} / {@link #batchExecutor(JdbcTemplate)}：
 *       与被测服务同源的 MyBatis Mapper / 批量执行器（被测代码不再接收 JdbcTemplate）。</li>
 * </ul>
 */
public final class SchemaTestSupport {

    /** schema 相对工程根目录的固定路径（Maven surefire 的工作目录 = 项目根）。 */
    private static final Path SCHEMA_PATH = Path.of("db", "schema.sql");

    /** 测试库文件目录（在 target 下，随 clean 清理）。 */
    private static final Path TEST_DB_DIR = Path.of("target", "test-db");

    /** 测试需要注册的 Mapper（与生产 @MapperScan 的包一致）。 */
    private static final List<Class<?>> MAPPERS = List.of(
            IngestLogMapper.class, SysConfigMapper.class, QualityCheckLogMapper.class,
            RateLimitCounterMapper.class, TushareTableMapper.class, TushareLookupMapper.class,
            QualityQueryMapper.class, OpsStatsMapper.class,
            SysUserMapper.class, SysUserSessionMapper.class, UserPreferenceMapper.class,
            UserWatchlistMapper.class);

    /** 每个测试库 JdbcTemplate 对应一个 SqlSessionFactory（测试方法间不串库）。 */
    private static final Map<JdbcTemplate, SqlSessionFactory> FACTORIES =
            Collections.synchronizedMap(new IdentityHashMap<>());

    private static final AtomicInteger DB_SEQUENCE = new AtomicInteger();

    private static volatile List<String> cachedStatements;

    private SchemaTestSupport() {
    }

    /** 新建一个随机名 SQLite 库文件（尚未建表）。 */
    public static DataSource newDataSource() {
        try {
            Files.createDirectories(TEST_DB_DIR);
        } catch (IOException e) {
            throw new UncheckedIOException("创建测试库目录失败: " + TEST_DB_DIR.toAbsolutePath(), e);
        }
        String name = "refineloop_" + DB_SEQUENCE.incrementAndGet() + "_"
                + UUID.randomUUID().toString().replace("-", "") + ".db";
        Path file = TEST_DB_DIR.resolve(name).toAbsolutePath();
        DriverManagerDataSource dataSource = new DriverManagerDataSource();
        dataSource.setDriverClassName("org.sqlite.JDBC");
        dataSource.setUrl(sqliteUrl(file));
        return dataSource;
    }

    /** 构造 SQLite JDBC URL（与生产 application.yml 保持同一组 pragma）。 */
    public static String sqliteUrl(Path file) {
        return "jdbc:sqlite:" + file + "?journal_mode=WAL&busy_timeout=5000"
                + "&date_class=TEXT&date_string_format=yyyy-MM-dd HH:mm:ss"
                + "&foreign_keys=on&synchronous=NORMAL";
    }

    /** 新建库 + 执行 {@code db/schema.sql}，返回可用的 JdbcTemplate。 */
    public static JdbcTemplate newJdbcTemplate() {
        JdbcTemplate jdbcTemplate = new JdbcTemplate(newDataSource());
        reset(jdbcTemplate);
        return jdbcTemplate;
    }

    /** 删除全部表后重新执行 schema（幂等重建测试库）。 */
    public static void reset(JdbcTemplate jdbcTemplate) {
        Objects.requireNonNull(jdbcTemplate, "jdbcTemplate 不能为空");
        // SQLite 的 DDL 是事务性的：把「删表 + 61 建表 + 74 建索引」放进一个事务，
        // 避免每条语句各自 fsync（否则每个测试方法建库要几秒）。
        TransactionTemplate transactionTemplate = new TransactionTemplate(
                new DataSourceTransactionManager(jdbcTemplate.getDataSource()));
        transactionTemplate.executeWithoutResult(status -> {
            dropAllTables(jdbcTemplate);
            for (String statement : statements()) {
                try {
                    jdbcTemplate.execute(statement);
                } catch (RuntimeException e) {
                    throw new IllegalStateException(
                            "执行 db/schema.sql 失败，语句: " + abbreviate(statement) + "；原因: " + e.getMessage(), e);
                }
            }
        });
    }

    /** 返回切分后的 schema 语句列表（只读，进程内缓存）。 */
    public static List<String> statements() {
        List<String> local = cachedStatements;
        if (local == null) {
            synchronized (SchemaTestSupport.class) {
                local = cachedStatements;
                if (local == null) {
                    local = Collections.unmodifiableList(loadStatements());
                    cachedStatements = local;
                }
            }
        }
        return local;
    }

    /** 读取 {@code db/schema.sql} 并切分语句。 */
    static List<String> loadStatements() {
        String raw;
        try {
            raw = Files.readString(SCHEMA_PATH, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException("读取 db/schema.sql 失败（工作目录应为工程根目录）: "
                    + SCHEMA_PATH.toAbsolutePath(), e);
        }
        return splitStatements(raw);
    }

    /** 按 {@code ;} 切分语句（忽略整行 {@code --} 注释与空语句）。 */
    static List<String> splitStatements(String sql) {
        StringBuilder withoutComments = new StringBuilder(sql.length());
        for (String line : sql.split("\\R")) {
            if (line.trim().startsWith("--")) {
                continue;
            }
            withoutComments.append(line).append('\n');
        }
        List<String> statements = new ArrayList<>();
        for (String part : withoutComments.toString().split(";")) {
            String statement = part.trim();
            if (!statement.isEmpty()) {
                statements.add(statement);
            }
        }
        return statements;
    }

    /** 删除库内全部表（含其索引）。 */
    static void dropAllTables(JdbcTemplate jdbcTemplate) {
        List<String> tables;
        try {
            tables = jdbcTemplate.queryForList(
                    "SELECT name FROM sqlite_master WHERE type = 'table' AND name NOT LIKE 'sqlite_%'", String.class);
        } catch (RuntimeException e) {
            return;   // 空库/新库
        }
        for (String table : tables) {
            jdbcTemplate.execute("DROP TABLE IF EXISTS \"" + table + "\"");
        }
    }

    private static String abbreviate(String statement) {
        String singleLine = statement.replaceAll("\\s+", " ");
        return singleLine.length() <= 240 ? singleLine : singleLine.substring(0, 240) + "...";
    }

    // ================================================================ MyBatis 测试组件

    /** 取（或构建）该测试库对应的 MyBatis 会话工厂。 */
    public static SqlSessionFactory sqlSessionFactory(JdbcTemplate jdbcTemplate) {
        Objects.requireNonNull(jdbcTemplate, "jdbcTemplate 不能为空");
        return FACTORIES.computeIfAbsent(jdbcTemplate,
                jt -> buildSqlSessionFactory(jt.getDataSource()));
    }

    /** 取该测试库上的 Mapper 实例（与被测服务使用同一套 MyBatis 配置/类型处理器）。 */
    public static <T> T mapper(JdbcTemplate jdbcTemplate, Class<T> mapperType) {
        return new SqlSessionTemplate(sqlSessionFactory(jdbcTemplate)).getMapper(mapperType);
    }

    /** 取该测试库上的 MyBatis 批量执行器（BATCH 执行器 + 短事务）。 */
    public static MyBatisBatchExecutor batchExecutor(JdbcTemplate jdbcTemplate) {
        IngestProperties properties = new IngestProperties();
        properties.setBatchSize(500);
        return new MyBatisBatchExecutor(sqlSessionFactory(jdbcTemplate),
                new DataSourceTransactionManager(jdbcTemplate.getDataSource()), properties);
    }

    private static SqlSessionFactory buildSqlSessionFactory(DataSource dataSource) {
        try {
            MybatisConfiguration configuration = new MybatisConfiguration();
            configuration.setMapUnderscoreToCamelCase(true);
            configuration.setJdbcTypeForNull(JdbcType.NULL);
            configuration.getTypeHandlerRegistry().register(java.time.LocalDateTime.class,
                    new SqliteLocalDateTimeTypeHandler());
            for (Class<?> mapperType : MAPPERS) {
                configuration.addMapper(mapperType);
            }
            // 与生产一致：注册分页拦截器（缺失时 IPage 查询不生成 LIMIT、total 恒为 0，属静默错误）。
            // 审计字段：实体写库前已显式 touch(...)，这里再注册 handler 保证与生产环境一致。
            GlobalConfig globalConfig = new GlobalConfig();
            globalConfig.setMetaObjectHandler(new AuditMetaObjectHandler(Clock.systemDefaultZone()));
            GlobalConfigUtils.setGlobalConfig(configuration, globalConfig);
            MybatisPlusInterceptor paginationInterceptor = new MybatisPlusInterceptor();
            PaginationInnerInterceptor pagination = new PaginationInnerInterceptor(DbType.SQLITE);
            pagination.setMaxLimit(100L);
            paginationInterceptor.addInnerInterceptor(pagination);
            configuration.addInterceptor(paginationInterceptor);
            MybatisSqlSessionFactoryBean factoryBean = new MybatisSqlSessionFactoryBean();
            factoryBean.setDataSource(dataSource);
            factoryBean.setConfiguration(configuration);
            return factoryBean.getObject();
        } catch (Exception e) {
            throw new IllegalStateException("构建测试用 SqlSessionFactory 失败: " + e.getMessage(), e);
        }
    }
}
