package com.liuzihe.interview.infra;

import com.liuzihe.interview.session.ReportStore;
import com.liuzihe.interview.session.ReportTask;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;

import javax.sql.DataSource;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * ReportStore 的 MySQL 实现：任务表即持久队列，进程重启不丢任务。
 * claim 用条件更新保证单领取（单并发 Worker 下加双保险）。
 */
public final class MySqlReportStore implements ReportStore {

    private static final RowMapper<ReportTask> MAPPER = (rs, i) -> new ReportTask(
            rs.getString("task_id"), rs.getString("session_id"), rs.getString("status"),
            rs.getInt("retry_count"), rs.getString("report_json"), rs.getString("error_msg"));

    private final JdbcTemplate jdbc;

    public MySqlReportStore(DataSource dataSource) {
        this.jdbc = new JdbcTemplate(dataSource);
    }

    @Override
    public ReportTask submit(String sessionId) {
        Optional<ReportTask> existing = loadBySession(sessionId);
        if (existing.isPresent()) {
            ReportTask task = existing.get();
            if (ReportTask.FAILED.equals(task.status())) {
                jdbc.update("UPDATE t_report SET status = 'GENERATING', retry_count = 0, error_msg = NULL"
                        + " WHERE task_id = ?", task.taskId());
                return new ReportTask(task.taskId(), sessionId, ReportTask.GENERATING, 0, null, null);
            }
            return task;
        }
        String taskId = "r-" + UUID.randomUUID().toString().substring(0, 8);
        jdbc.update("INSERT INTO t_report (task_id, session_id, status) VALUES (?, ?, 'GENERATING')",
                taskId, sessionId);
        return new ReportTask(taskId, sessionId, ReportTask.GENERATING, 0, null, null);
    }

    @Override
    public Optional<ReportTask> claim() {
        List<String> ids = jdbc.query(
                "SELECT task_id FROM t_report WHERE status = 'GENERATING' ORDER BY created_at LIMIT 1",
                (rs, i) -> rs.getString("task_id"));
        if (ids.isEmpty()) {
            return Optional.empty();
        }
        int updated = jdbc.update(
                "UPDATE t_report SET status = 'RUNNING' WHERE task_id = ? AND status = 'GENERATING'", ids.get(0));
        if (updated == 0) {
            return Optional.empty();
        }
        return findById(ids.get(0));
    }

    @Override
    public void complete(String taskId, String reportJson) {
        jdbc.update("UPDATE t_report SET status = 'DONE', report_json = ?, error_msg = NULL"
                + " WHERE task_id = ?", reportJson, taskId);
    }

    @Override
    public void fail(String taskId, String errorMsg) {
        jdbc.update("""
                        UPDATE t_report SET retry_count = retry_count + 1,
                          status = IF(retry_count + 1 >= 3, 'FAILED', 'GENERATING'),
                          error_msg = ? WHERE task_id = ?""",
                truncate(errorMsg), taskId);
    }

    @Override
    public Optional<ReportTask> loadBySession(String sessionId) {
        List<ReportTask> found = jdbc.query(
                "SELECT task_id, session_id, status, retry_count, report_json, error_msg"
                        + " FROM t_report WHERE session_id = ?", MAPPER, sessionId);
        return found.isEmpty() ? Optional.empty() : Optional.of(found.get(0));
    }

    @Override
    public int resetStaleRunning() {
        return jdbc.update("UPDATE t_report SET status = 'GENERATING' WHERE status = 'RUNNING'");
    }

    private Optional<ReportTask> findById(String taskId) {
        List<ReportTask> found = jdbc.query(
                "SELECT task_id, session_id, status, retry_count, report_json, error_msg"
                        + " FROM t_report WHERE task_id = ?", MAPPER, taskId);
        return found.isEmpty() ? Optional.empty() : Optional.of(found.get(0));
    }

    private static String truncate(String message) {
        if (message == null) {
            return null;
        }
        return message.length() <= 500 ? message : message.substring(0, 500);
    }
}
