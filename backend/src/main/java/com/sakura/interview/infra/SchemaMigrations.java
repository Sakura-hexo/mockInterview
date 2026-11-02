package com.sakura.interview.infra;

import org.springframework.jdbc.core.JdbcTemplate;

import javax.sql.DataSource;
import java.util.List;

/**
 * 存量库列迁移：MySQL 不支持 ADD COLUMN IF NOT EXISTS，
 * 逐一查 information_schema 判断后再 ALTER，幂等可重复执行。
 * 建表脚本负责新装库，本类负责把旧库补齐到最新列集。
 */
public final class SchemaMigrations {

    private SchemaMigrations() {
    }

    /** 迁移清单：表、列、列定义。执行完返回补了多少列。 */
    public static int migrate(DataSource dataSource) {
        JdbcTemplate jdbc = new JdbcTemplate(dataSource);
        int applied = 0;
        applied += addColumn(jdbc, "t_session", "user_id", "BIGINT NULL");
        applied += addColumn(jdbc, "t_session", "difficulty", "VARCHAR(16) NOT NULL DEFAULT 'MEDIUM'");
        applied += addColumn(jdbc, "t_session", "question_count", "INT NOT NULL DEFAULT 10");
        applied += addColumn(jdbc, "t_message", "action", "VARCHAR(16) NULL");
        ensureIndexColumns(jdbc, "t_session", "idx_session_user", "user_id, created_at");
        return applied;
    }

    private static int addColumn(JdbcTemplate jdbc, String table, String column, String definition) {
        List<String> existing = jdbc.queryForList(
                "SELECT column_name FROM information_schema.columns"
                        + " WHERE table_schema = DATABASE() AND table_name = ? AND column_name = ?",
                String.class, table, column);
        if (!existing.isEmpty()) {
            return 0;
        }
        jdbc.execute("ALTER TABLE " + table + " ADD COLUMN " + column + " " + definition);
        System.err.printf("[迁移] %s 增加 %s 列%n", table, column);
        return 1;
    }

    /** 索引列集迁移：列集不一致时重建（无 CREATE INDEX IF NOT EXISTS，也无改列语法），幂等。 */
    private static void ensureIndexColumns(JdbcTemplate jdbc, String table, String index, String columns) {
        List<String> existing = jdbc.queryForList(
                "SELECT column_name FROM information_schema.statistics"
                        + " WHERE table_schema = DATABASE() AND table_name = ? AND index_name = ?"
                        + " ORDER BY seq_in_index",
                String.class, table, index);
        String expected = String.join(", ", columns.split("\\s*,\\s*"));
        if (String.join(", ", existing).equals(expected)) {
            return;
        }
        if (!existing.isEmpty()) {
            jdbc.execute("ALTER TABLE " + table + " DROP INDEX " + index);
        }
        jdbc.execute("CREATE INDEX " + index + " ON " + table + " (" + expected + ")");
        System.err.printf("[迁移] %s 重建 %s 索引为列集 %s%n", table, index, expected);
    }
}
