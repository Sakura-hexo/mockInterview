package com.liuzihe.interview.infra;

import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;
import com.liuzihe.interview.session.SessionState;
import com.liuzihe.interview.session.SessionSummary;
import com.liuzihe.interview.session.StoredMessage;
import com.liuzihe.interview.tool.JdProfile;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;

import javax.sql.DataSource;
import java.lang.reflect.Type;
import java.util.Collections;
import java.util.List;
import java.util.Optional;

/**
 * SessionStore 的 MySQL 实现：会话数据的事实源，先写本库、后写缓存。
 * seq 由 MAX 加 1 分配，单进程单写者无并发竞争。
 */
public final class MySqlSessionStore {

    private static final Type STRING_LIST = new TypeToken<List<String>>() {
    }.getType();

    private static final RowMapper<StoredMessage> MESSAGE_MAPPER = (rs, i) -> new StoredMessage(
            rs.getInt("seq"), rs.getString("role"), rs.getString("content"),
            rs.getLong("input_tokens"), rs.getLong("output_tokens"));

    private final JdbcTemplate jdbc;
    private final Gson gson = new Gson();

    public MySqlSessionStore(DataSource dataSource) {
        this.jdbc = new JdbcTemplate(dataSource);
    }

    public void saveSession(SessionState state) {
        JdProfile jd = state.jdProfile();
        jdbc.update("""
                        INSERT INTO t_session (session_id, resume_digest, jd_direction, jd_skills,
                            jd_bonus, asked_questions_json, round_no, status)
                        VALUES (?, ?, ?, ?, ?, ?, ?, 'ACTIVE')
                        ON DUPLICATE KEY UPDATE
                          resume_digest = VALUES(resume_digest), jd_direction = VALUES(jd_direction),
                          jd_skills = VALUES(jd_skills), jd_bonus = VALUES(jd_bonus),
                          asked_questions_json = VALUES(asked_questions_json),
                          round_no = VALUES(round_no), status = 'ACTIVE'""",
                state.sessionId(), state.resumeDigest(), jd.direction(),
                gson.toJson(jd.skills()), gson.toJson(jd.bonus()),
                gson.toJson(state.askedQuestions()), state.round());
    }

    public Optional<SessionState> loadSession(String sessionId) {
        List<SessionState> found = jdbc.query(
                "SELECT resume_digest, jd_direction, jd_skills, jd_bonus, asked_questions_json, round_no"
                        + " FROM t_session WHERE session_id = ?",
                (rs, i) -> new SessionState(sessionId, rs.getString("resume_digest"),
                        new JdProfile(rs.getString("jd_direction"),
                                parseList(rs.getString("jd_skills")),
                                parseList(rs.getString("jd_bonus"))),
                        parseList(rs.getString("asked_questions_json")), rs.getInt("round_no")),
                sessionId);
        return found.isEmpty() ? Optional.empty() : Optional.of(found.get(0));
    }

    public int appendMessage(String sessionId, String role, String text,
                             long inputTokens, long outputTokens) {
        Integer max = jdbc.queryForObject(
                "SELECT COALESCE(MAX(seq), 0) FROM t_message WHERE session_id = ?",
                Integer.class, sessionId);
        int seq = (max == null ? 0 : max) + 1;
        jdbc.update("INSERT INTO t_message (session_id, seq, role, content, input_tokens, output_tokens)"
                        + " VALUES (?, ?, ?, ?, ?, ?)",
                sessionId, seq, role, text, inputTokens, outputTokens);
        return seq;
    }

    /** 全量消息升序，供 Redis 缓存丢失后重建。 */
    public List<StoredMessage> readAll(String sessionId) {
        return jdbc.query("SELECT seq, role, content, input_tokens, output_tokens"
                + " FROM t_message WHERE session_id = ? ORDER BY seq", MESSAGE_MAPPER, sessionId);
    }

    public List<StoredMessage> readWindow(String sessionId, int limit) {
        List<StoredMessage> desc = jdbc.query(
                "SELECT seq, role, content, input_tokens, output_tokens FROM t_message"
                        + " WHERE session_id = ? ORDER BY seq DESC LIMIT ?",
                MESSAGE_MAPPER, sessionId, limit);
        Collections.reverse(desc);
        return desc;
    }

    public List<StoredMessage> readBatch(String sessionId, int afterSeq, int limit) {
        return jdbc.query("SELECT seq, role, content, input_tokens, output_tokens FROM t_message"
                        + " WHERE session_id = ? AND seq > ? ORDER BY seq LIMIT ?",
                MESSAGE_MAPPER, sessionId, afterSeq, limit);
    }

    public Optional<SessionSummary> loadSummary(String sessionId) {
        List<SessionSummary> found = jdbc.query(
                "SELECT summary_text, covered_seq FROM t_context_summary WHERE session_id = ?",
                (rs, i) -> new SessionSummary(rs.getString("summary_text"), rs.getInt("covered_seq")),
                sessionId);
        return found.isEmpty() ? Optional.empty() : Optional.of(found.get(0));
    }

    public void saveSummary(String sessionId, String text, int coveredSeq) {
        jdbc.update("""
                        INSERT INTO t_context_summary (session_id, summary_text, covered_seq)
                        VALUES (?, ?, ?)
                        ON DUPLICATE KEY UPDATE
                          summary_text = VALUES(summary_text), covered_seq = VALUES(covered_seq)""",
                sessionId, text, coveredSeq);
    }

    public void deleteSession(String sessionId) {
        jdbc.update("DELETE FROM t_message WHERE session_id = ?", sessionId);
        jdbc.update("DELETE FROM t_context_summary WHERE session_id = ?", sessionId);
        jdbc.update("DELETE FROM t_session WHERE session_id = ?", sessionId);
    }

    private List<String> parseList(String json) {
        if (json == null || json.isBlank() || "null".equals(json)) {
            return List.of();
        }
        List<String> parsed = gson.fromJson(json, STRING_LIST);
        return parsed == null ? List.of() : parsed;
    }
}
