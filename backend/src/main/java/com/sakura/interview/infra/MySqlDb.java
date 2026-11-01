package com.sakura.interview.infra;

import com.zaxxer.hikari.HikariDataSource;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.datasource.init.ScriptUtils;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.SQLException;

/**
 * MySQL 接入：连接池工厂与会话表初始化。库不存在时由 URL 参数自动创建，
 * 表结构在 classpath 的 sql/session-schema.sql，幂等可重复执行。
 */
public final class MySqlDb {

    private MySqlDb() {
    }

    /** 会话持久化连接池：CLI 单进程小池即可。 */
    public static HikariDataSource dataSource() {
        HikariDataSource ds = new HikariDataSource();
        ds.setPoolName("interview-session");
        ds.setJdbcUrl(EnvKeys.mysqlUrl());
        ds.setUsername(EnvKeys.mysqlUsername());
        ds.setPassword(EnvKeys.mysqlPassword());
        ds.setMaximumPoolSize(3);
        return ds;
    }

    /** 执行建表脚本（幂等），并把存量库补齐到最新列集。 */
    public static void init(DataSource dataSource) {
        try (Connection connection = dataSource.getConnection()) {
            ScriptUtils.executeSqlScript(connection,
                    new ClassPathResource("sql/session-schema.sql"));
            ScriptUtils.executeSqlScript(connection,
                    new ClassPathResource("sql/report-schema.sql"));
            ScriptUtils.executeSqlScript(connection,
                    new ClassPathResource("sql/auth-schema.sql"));
        } catch (SQLException e) {
            throw new IllegalStateException("初始化会话表失败：", e);
        }
        SchemaMigrations.migrate(dataSource);
    }
}
