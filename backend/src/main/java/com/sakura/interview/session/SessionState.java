package com.sakura.interview.session;

import java.util.ArrayList;
import java.util.List;

import com.sakura.interview.tool.JdProfile;

/**
 * 会话状态：常驻层元数据（简历摘要、岗位要点、已问题目清单、轮次、归属用户、配置与状态）。
 * 每轮从存储层加载、变更后写回，进程重启可恢复。
 * askedQuestions 存面试官每轮输出摘要注入下一轮系统提示词防止重复出题；
 * 单条与总量都设上限，更早的题目由滚动前情摘要覆盖。
 * status 状态机：CREATED → IN_PROGRESS → ENDED → REPORTED，由接口层推进，不在本类流转。
 */
public final class SessionState {

    public static final String CREATED = "CREATED";
    public static final String IN_PROGRESS = "IN_PROGRESS";
    public static final String ENDED = "ENDED";
    public static final String REPORTED = "REPORTED";

    static final int ASKED_SUMMARY_MAX_CHARS = 60;
    static final int ASKED_KEEP_MAX = 40;

    private final String sessionId;
    private final Long userId;
    private final String resumeDigest;
    private final JdProfile jdProfile;
    private final SessionConfig config;
    private final String status;
    private final String createdAt;
    private final List<String> askedQuestions = new ArrayList<>();
    private int round;

    public SessionState(String sessionId, String resumeDigest, JdProfile jdProfile) {
        this(sessionId, resumeDigest, jdProfile, List.of(), 0);
    }

    /** 恢复构造：从存储层回读已问题目清单与轮次。 */
    public SessionState(String sessionId, String resumeDigest, JdProfile jdProfile,
                        List<String> askedQuestions, int round) {
        this(sessionId, null, resumeDigest, jdProfile,
                defaultConfig(), CREATED, null, askedQuestions, round);
    }

    /** 全量构造：Web 建会话带用户与配置，CLI 会话 userId 与 createdAt 为空。 */
    public SessionState(String sessionId, Long userId, String resumeDigest, JdProfile jdProfile,
                        SessionConfig config, String status, String createdAt,
                        List<String> askedQuestions, int round) {
        this.sessionId = sessionId;
        this.userId = userId;
        this.resumeDigest = resumeDigest;
        this.jdProfile = jdProfile;
        this.config = config == null ? defaultConfig() : config;
        this.status = status == null ? CREATED : status;
        this.createdAt = createdAt;
        this.askedQuestions.addAll(askedQuestions);
        this.round = round;
    }

    /** CLI 与旧数据缺省配置：难度中档、默认 10 题。 */
    private static SessionConfig defaultConfig() {
        return new SessionConfig("JAVA_BACKEND", "MEDIUM", 10);
    }

    /** 记录面试官本轮输出为已问题目摘要并推进轮次，超限时裁掉最早的。 */
    public void recordAsked(String interviewerReply) {
        String summary = interviewerReply == null ? "" : interviewerReply.strip();
        if (summary.length() > ASKED_SUMMARY_MAX_CHARS) {
            summary = summary.substring(0, ASKED_SUMMARY_MAX_CHARS) + "…";
        }
        if (!summary.isEmpty()) {
            askedQuestions.add(summary);
            if (askedQuestions.size() > ASKED_KEEP_MAX) {
                askedQuestions.subList(0, askedQuestions.size() - ASKED_KEEP_MAX).clear();
            }
        }
        round++;
    }

    /** 概览视图：历史列表与会话详情头部共用（接口文档第 3 节，方向取 JD 解析文本）。 */
    public SessionOverview overview() {
        return new SessionOverview(sessionId, status, jdProfile.direction(),
                config.difficulty(), config.questionCount(), createdAt);
    }

    public String sessionId() {
        return sessionId;
    }

    public Long userId() {
        return userId;
    }

    public String resumeDigest() {
        return resumeDigest;
    }

    public JdProfile jdProfile() {
        return jdProfile;
    }

    public SessionConfig config() {
        return config;
    }

    public String status() {
        return status;
    }

    public String createdAt() {
        return createdAt;
    }

    public int round() {
        return round;
    }

    public List<String> askedQuestions() {
        return List.copyOf(askedQuestions);
    }
}
