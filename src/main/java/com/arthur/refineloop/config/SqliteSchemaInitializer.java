package com.arthur.refineloop.config;

import org.apache.ibatis.jdbc.ScriptRunner;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.ClassPathResource;
import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.Resource;
import org.springframework.stereotype.Component;

import javax.sql.DataSource;
import jakarta.annotation.PostConstruct;

import java.io.IOException;
import java.io.InputStreamReader;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.SQLException;

/**
 * SQLite 库初始化：确保库文件目录存在，并在启动时执行 {@code db/schema.sql} 建表。
 *
 * <p>数据库引擎为 SQLite（嵌入式单文件库），没有独立的建库/建用户步骤，因此把「建表」收敛到启动期：
 * <ol>
 *   <li>从 {@code spring.datasource.url} 解析库文件路径，自动创建父目录（如 {@code ./data}）；</li>
 *   <li>读取 schema（优先 classpath 下的 {@code db/schema.sql}，jar 运行时由 Maven 资源复制；
 *       IDE/源码运行时回退工程根目录 {@code db/schema.sql}）；</li>
 *   <li>用 MyBatis 的 {@link ScriptRunner} 执行整个脚本（DDL 属于 MyBatis 的 ScriptRunner 职责，
 *       不需要 JdbcTemplate；schema 全部语句是 {@code IF NOT EXISTS}，重复启动幂等安全）。</li>
 * </ol>
 *
 * <p>非 SQLite 数据源（单测注入其它 URL）跳过；执行失败直接让应用启动失败（fail-fast），
 * 避免带着半个 schema 启动后产生难排查的运行时错误。
 */
@Component
public class SqliteSchemaInitializer {

    private static final Logger log = LoggerFactory.getLogger(SqliteSchemaInitializer.class);
    private static final String SQLITE_PREFIX = "jdbc:sqlite:";

    /** schema 在 classpath 与工程根目录下的位置。 */
    private static final String CLASSPATH_SCHEMA = "db/schema.sql";
    private static final Path FILESYSTEM_SCHEMA = Path.of("db", "schema.sql");

    private final DataSource dataSource;
    private final String datasourceUrl;

    public SqliteSchemaInitializer(DataSource dataSource,
                                   @Value("${spring.datasource.url:}") String datasourceUrl) {
        this.dataSource = dataSource;
        this.datasourceUrl = datasourceUrl;
    }

    @PostConstruct
    public void initialize() {
        if (datasourceUrl == null || !datasourceUrl.startsWith(SQLITE_PREFIX)) {
            log.debug("非 SQLite 数据源，跳过 schema 初始化: {}", mask(datasourceUrl));
            return;
        }
        ensureParentDirectory();
        Resource schema = locateSchema();
        try (Connection connection = dataSource.getConnection();
             Reader reader = new InputStreamReader(schema.getInputStream(), StandardCharsets.UTF_8)) {
            ScriptRunner runner = new ScriptRunner(connection);
            // 静默 ScriptRunner 自带日志（结果只由本类统一 INFO 汇报）
            runner.setLogWriter(null);
            runner.setErrorLogWriter(null);
            runner.setAutoCommit(true);
            runner.setStopOnError(true);
            runner.runScript(reader);
        } catch (SQLException | IOException e) {
            throw new IllegalStateException("执行 db/schema.sql 失败（库文件=" + databaseFile() + "）: "
                    + e.getMessage(), e);
        }
        log.info("SQLite schema 已应用（幂等）：库文件={}", databaseFile());
    }

    /** 解析 URL 中的库文件路径（去掉 query 参数）；非文件库返回 null。 */
    private String databaseFile() {
        String path = datasourceUrl.substring(SQLITE_PREFIX.length());
        int queryIndex = path.indexOf('?');
        if (queryIndex >= 0) {
            path = path.substring(0, queryIndex);
        }
        if (path.isBlank() || path.equals(":memory:") || path.startsWith("file:")) {
            return null;
        }
        return path;
    }

    private void ensureParentDirectory() {
        String file = databaseFile();
        if (file == null) {
            return;
        }
        try {
            Path parent = Path.of(file).toAbsolutePath().getParent();
            if (parent != null) {
                Files.createDirectories(parent);
            }
        } catch (IOException e) {
            throw new IllegalStateException("创建 SQLite 库文件目录失败: " + file, e);
        }
    }

    private Resource locateSchema() {
        ClassPathResource classpath = new ClassPathResource(CLASSPATH_SCHEMA);
        if (classpath.exists()) {
            return classpath;
        }
        FileSystemResource filesystem = new FileSystemResource(FILESYSTEM_SCHEMA);
        if (filesystem.exists()) {
            return filesystem;
        }
        throw new IllegalStateException("找不到 db/schema.sql（classpath:" + CLASSPATH_SCHEMA
                + " 与 " + FILESYSTEM_SCHEMA.toAbsolutePath() + " 均不存在）");
    }

    private static String mask(String url) {
        return url == null ? "" : url.replaceAll("(?i)(password|pwd)=[^&;]*", "$1=***");
    }
}
