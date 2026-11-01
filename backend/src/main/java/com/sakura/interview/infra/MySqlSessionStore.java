package com.sakura.interview.infra;

import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;
import com.sakura.interview.session.SessionConfig;
import com.sakura.interview.session.SessionOverview;
import com.sakura.interview.session.SessionState;
import com.sakura.interview.session.SessionStore;
import com.sakura.interview.session.SessionSummary;
import com.sakura.interview.session.StoredMessage;
import com.sakura.interview.tool.JdProfile;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;

import javax.sql.DataSource;
import java.lang.reflect.Type;
import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Collections;
import java.util.List;
import java.util.Optional;

/**
 * SessionStore 的 MySQL 实现：会话数据的事实源，先写本库、后写缓存。
 * seq 由 MAX 加 1 分配，同会话的并发发送由 SSE 层单飞守卫挡住，不并发追加。
 * upsert 不改写 status 与 user_id——状态机由接口层显式推进，归属创建后不可变。
 */
public final class MySqlSessionStore implements SessionStore {

    private static final Type STRING_LIST = new TypeToken<List<String>>() {
    }.getType();

    private static final DateTimeFormatter ISO = DateTimeFormatter.ISO_LOCAL_DATE_TIME;

    private static final String MESSAGE_COLUMNS =
            "seq, role, content, input_tokens, output_tokens, action, created_at";

    private static final RowMapper<StoredMessage> MESSAGE_MAPPER = (rs, i) -> {
        Timestamp time = rs.getTimestamp("created_at");
        return new StoredMessage(rs.getInt("seq"), rs.getString("role"), rs.getString("content"),
                rs.getLong("input_tokens"), rs.getLong("output_tokens"), rs.getString("action"),
                time == null ? null : ISO.format(time.toLocalDateTime()));
    };

    private static final RowMapper<SessionOverview> OVERVIEW_MAPPER = (rs, i) -> new SessionOverview(
            rs.getString("session_id"), rs.getString("status"), rs.getString("jd_direction"),
            rs.getString("difficulty"), rs.getInt("question_count"),
            Optional.ofNullable(rs.getTimestamp("created_at")).map(Timestamp::toLocalDateTime)
                    .map(ISO::format).orElse(null));

    private final JdbcTemplate jdbc;
    private final Gson gson = new Gson();

    public MySqlSessionStore(DataSource dataSource) {
        this.jdbc = new JdbcTemplate(dataSource);
    }

    @Override
    public void saveSession(SessionState state) {
        JdProfile jd = state.jdProfile();
        SessionConfig config = state.config();
        jdbc.update("""
                        INSERT INTO t_session (session_id, user_id, resume_digest, jd_direction, jd_skills,
                            jd_bonus, asked_questions_json, round_no, difficulty, question_count, status)
                        VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                        ON DUPLICATE KEY UPDATE
                          resume_digest = VALUES(resume_digest), jd_direction = VALUES(jd_direction),
                          jd_skills = VALUES(jd_skills), jd_bonus = VALUES(jd_bonus),
                          asked_questions_json = VALUES(asked_questions_json),
                          round_no = VALUES(round_no), difficulty = VALUES(difficulty),
                          question_count = VALUES(question_count)""",
                state.sessionId(), state.userId(), state.resumeDigest(), jd.direction(),
                gson.toJson(jd.skills()), gson.toJson(jd.bonus()),
                gson.toJson(state.askedQuestions()), state.round(),
                config.difficulty(), config.questionCount(), state.status());
    }

    @Override
    public Optional<SessionState> loadSession(String sessionId) {
        List<SessionState> found = jdbc.query(
                "SELECT user_id, resume_digest, jd_direction, jd_skills, jd_bonus,"
                        + " asked_questions_json, round_no, difficulty, question_count, status, created_at"
                        + " FROM t_session WHERE session_id = ?",
                (rs, i) -> new SessionState(sessionId,
                        rs.getObject("user_id") == null ? null : rs.getLong("user_id"),
                        rs.getString("resume_digest"),
                        new JdProfile(rs.getString("jd_direction"),
                                parseList(rs.getString("jd_skills")),
                                parseList(rs.getString("jd_bonus"))),
                        new SessionConfig("JAVA_BACKEND", rs.getString("difficulty"),
                                rs.getInt("question_count")),
                        rs.getString("status"), formatTime(rs.getTimestamp("created_at")),
                        parseList(rs.getString("asked_questions_json")), rs.getInt("round_no")),
                sessionId);
        return found.isEmpty() ? Optional.empty() : Optional.of(found.get(0));
    }

    @Override
    public void updateStatus(String sessionId, String status) {
        jdbc.update("UPDATE t_session SET status = ? WHERE session_id = ?", status, sessionId);
    }

    @Override
    public boolean casStatus(String sessionId, String from, String to) {
        return jdbc.update("UPDATE t_session SET status = ? WHERE session_id = ? AND status = ?",
                to, sessionId, from) > 0;
    }

    @Override
    public int appendMessage(String sessionId, String role, String text,
                             long inputTokens, long outputTokens, String action) {
        return appendMessage(sessionId, role, text, inputTokens, outputTokens, action,
                LocalDateTime.now().truncatedTo(java.time.temporal.ChronoUnit.SECONDS));
    }

    /** 带显式时间戳的追加：缓存层用同一时间戳写 Redis，保证两份数据一致。 */
    public int appendMessage(String sessionId, String role, String text,
                             long inputTokens, long outputTokens, String action,
                             LocalDateTime createdAt) {
        Integer max = jdbc.queryForObject(
                "SELECT COALESCE(MAX(seq), 0) FROM t_message WHERE session_id = ?",
                Integer.class, sessionId);
        int seq = (max == null ? 0 : max) + 1;
        jdbc.update("INSERT INTO t_message (session_id, seq, role, content,"
                        + " input_tokens, output_tokens, action, created_at)"
                        + " VALUES (?, ?, ?, ?, ?, ?, ?, ?)",
                sessionId, seq, role, text, inputTokens, outputTokens, action, createdAt);
        return seq;
    }

    @Override
    public List<StoredMessage> readAll(String sessionId) {
        return jdbc.query("SELECT " + MESSAGE_COLUMNS
                + " FROM t_message WHERE session_id = ? ORDER BY seq", MESSAGE_MAPPER, sessionId);
    }

    @Override
    public int countQuestions(String sessionId) {
        Integer count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM t_message WHERE session_id = ? AND role = 'interviewer'"
                        + " AND action IN ('QUESTION', 'NEXT')",
                Integer.class, sessionId);
        return count == null ? 0 : count;
    }

    @Override
    public List<StoredMessage> readWindow(String sessionId, int limit) {
        List<StoredMessage> desc = jdbc.query(
                "SELECT " + MESSAGE_COLUMNS + " FROM t_message"
                        + " WHERE session_id = ? ORDER BY seq DESC LIMIT ?",
                MESSAGE_MAPPER, sessionId, limit);
        Collections.reverse(desc);
        return desc;
    }

    @Override
    public List<StoredMessage> readBatch(String sessionId, int afterSeq, int limit) {
        return jdbc.query("SELECT " + MESSAGE_COLUMNS + " FROM t_message"
                        + " WHERE session_id = ? AND seq > ? ORDER BY seq LIMIT ?",
                MESSAGE_MAPPER, sessionId, afterSeq, limit);
    }

    @Override
    public List<SessionOverview> listOverviews(Long userId, int page, int size) {
        int offset = Math.max(0, page - 1) * size;
        if (userId == null) {
            return jdbc.query("SELECT session_id, status, jd_direction, difficulty, question_count,"
                            + " created_at FROM t_session ORDER BY created_at DESC LIMIT ? OFFSET ?",
                    OVERVIEW_MAPPER, size, offset);
        }
        return jdbc.query("SELECT session_id, status, jd_direction, difficulty, question_count,"
                        + " created_at FROM t_session WHERE user_id = ?"
                        + " ORDER BY created_at DESC LIMIT ? OFFSET ?",
                OVERVIEW_MAPPER, userId, size, offset);
    }

    @Override
    public long countOverviews(Long userId) {
        Long count = userId == null
                ? jdbc.queryForObject("SELECT COUNT(*) FROM t_session", Long.class)
                : jdbc.queryForObject("SELECT COUNT(*) FROM t_session WHERE user_id = ?",
                        Long.class, userId);
        return count == null ? 0 : count;
    }

    @Override
    public Optional<SessionSummary> loadSummary(String sessionId) {
        List<SessionSummary> found = jdbc.query(
                "SELECT summary_text, covered_seq FROM t_context_summary WHERE session_id = ?",
                (rs, i) -> new SessionSummary(rs.getString("summary_text"), rs.getInt("covered_seq")),
                sessionId);
        return found.isEmpty() ? Optional.empty() : Optional.of(found.get(0));
    }

    @Override
    public void saveSummary(String sessionId, String text, int coveredSeq) {
        jdbc.update("""
                        INSERT INTO t_context_summary (session_id, summary_text, covered_seq)
                        VALUES (?, ?, ?)
                        ON DUPLICATE KEY UPDATE
                          summary_text = VALUES(summary_text), covered_seq = VALUES(covered_seq)""",
                sessionId, text, coveredSeq);
    }

    @Override
    public void deleteSession(String sessionId) {
        jdbc.update("DELETE FROM t_message WHERE session_id = ?", sessionId);
        jdbc.update("DELETE FROM t_context_summary WHERE session_id = ?", sessionId);
        jdbc.update("DELETE FROM t_session WHERE session_id = ?", sessionId);
    }

    @Override
    public void endSession(String sessionId) {
        jdbc.update("UPDATE t_session SET status = 'ENDED', ended_at = NOW() WHERE session_id = ?",
                sessionId);
    }

    @Override
    public long durationMs(String sessionId) {
        List<Long> spans = jdbc.query(
                "SELECT TIMESTAMPDIFF(MICROSECOND, MIN(created_at), MAX(created_at))"
                        + " FROM t_message WHERE session_id = ?",
                (rs, i) -> rs.getLong(1) / 1000, sessionId);
        return spans.isEmpty() ? 0 : spans.get(0);
    }

    private static String formatTime(Timestamp time) {
        return time == null ? null : ISO.format(time.toLocalDateTime());
    }

    private List<String> parseList(String json) {
        if (json == null || json.isBlank() || "null".equals(json)) {
            return List.of();
        }
        List<String> parsed = gson.fromJson(json, STRING_LIST);
        return parsed == null ? List.of() : parsed;
    }
}
