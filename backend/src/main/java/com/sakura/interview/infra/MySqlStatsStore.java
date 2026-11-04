package com.sakura.interview.infra;

import com.sakura.interview.api.StatsStore;
import org.springframework.jdbc.core.JdbcTemplate;

import javax.sql.DataSource;
import java.util.List;

/**
 * StatsStore 的 MySQL 实现：token 按消息口径聚合（t_message 逐条带 token 与
 * created_at），按日分桶用 DATE(created_at)——消息落库即事件完成，同步写入无迟到
 * 数据，不需要流式统计的 watermark 宽限；按会话/按轮归组反而会引入跨天归属歧义。
 * 用户消息 token 恒为 0，求和天然只计模型侧消耗。
 */
public final class MySqlStatsStore implements StatsStore {

    private static final String USER_FILTER =
            " FROM t_message m JOIN t_session s ON m.session_id = s.session_id WHERE s.user_id = ?";

    private final JdbcTemplate jdbc;

    public MySqlStatsStore(DataSource dataSource) {
        this.jdbc = new JdbcTemplate(dataSource);
    }

    @Override
    public TokenSummary userSummary(long userId) {
        return jdbc.queryForObject(
                "SELECT COALESCE(SUM(m.input_tokens), 0), COALESCE(SUM(m.output_tokens), 0),"
                        + " COUNT(DISTINCT m.session_id), COUNT(*)" + USER_FILTER,
                (rs, i) -> new TokenSummary(rs.getLong(1), rs.getLong(2), rs.getInt(3), rs.getInt(4)),
                userId);
    }

    @Override
    public TokenSummary siteSummary() {
        return jdbc.queryForObject(
                "SELECT COALESCE(SUM(m.input_tokens), 0), COALESCE(SUM(m.output_tokens), 0),"
                        + " COUNT(DISTINCT m.session_id), COUNT(*) FROM t_message m",
                (rs, i) -> new TokenSummary(rs.getLong(1), rs.getLong(2), rs.getInt(3), rs.getInt(4)));
    }

    @Override
    public List<DailyPoint> dailyTrend(long userId, int days) {
        return jdbc.query(
                "SELECT DATE_FORMAT(m.created_at, '%Y-%m-%d') AS d,"
                        + " COALESCE(SUM(m.input_tokens + m.output_tokens), 0)" + USER_FILTER
                        + " AND m.created_at >= CURDATE() - INTERVAL ? DAY GROUP BY d ORDER BY d",
                (rs, i) -> new DailyPoint(rs.getString(1), rs.getLong(2)),
                userId, days - 1);
    }

    @Override
    public List<SessionRow> recentSessions(long userId, int limit) {
        return jdbc.query(
                "SELECT s.session_id, s.jd_direction, s.difficulty,"
                        + " DATE_FORMAT(s.created_at, '%Y-%m-%d %H:%i'), COUNT(m.seq),"
                        + " COALESCE(SUM(m.input_tokens + m.output_tokens), 0)"
                        + " FROM t_session s LEFT JOIN t_message m ON m.session_id = s.session_id"
                        + " WHERE s.user_id = ? GROUP BY s.session_id"
                        + " ORDER BY s.created_at DESC LIMIT ?",
                (rs, i) -> new SessionRow(rs.getString(1), rs.getString(2), rs.getString(3),
                        rs.getString(4), rs.getInt(5), rs.getLong(6)),
                userId, limit);
    }

    @Override
    public List<UserRow> topUsers(int limit) {
        return jdbc.query(
                "SELECT u.username, COUNT(DISTINCT s.session_id),"
                        + " COALESCE(SUM(m.input_tokens + m.output_tokens), 0)"
                        + " FROM t_user u LEFT JOIN t_session s ON s.user_id = u.id"
                        + " LEFT JOIN t_message m ON m.session_id = s.session_id"
                        + " GROUP BY u.id, u.username ORDER BY 3 DESC, u.id LIMIT ?",
                (rs, i) -> new UserRow(rs.getString(1), rs.getInt(2), rs.getLong(3)),
                limit);
    }
}
