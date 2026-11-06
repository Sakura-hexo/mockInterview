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
 * 建会话先落占位（markAnalysisPending 暂存简历与 JD 原文），后台分析完成后经
 * applyAnalysis 回填真值——摘要与画像因此可变，回填只发生在分析完成这一个时点。
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
    private final SessionConfig config;
    private final String status;
    private final String createdAt;
    private final List<String> askedQuestions = new ArrayList<>();
    private int round;
    /** 无效作答连击：模型按「选题 无效」标记判定，无效加一、切题清零，三振由 Supervisor 收场。 */
    private int invalidStreak;

    private String resumeDigest;
    private JdProfile jdProfile;
    /** 分析是否仍为占位：true 时摘要与画像不可信，原文两字段供补跑分析。 */
    private volatile boolean analysisPending;
    private String resumeText;
    private String jdText;

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

    /** 无效作答连击记账：本轮回复带无效标记则加一，否则清零（问满收尾轮无标记，自然清零无害）。 */
    public void recordAnswerValidity(boolean valid) {
        this.invalidStreak = valid ? 0 : this.invalidStreak + 1;
    }

    /** 存储层回读恢复连击计数（与 round 同类的持久化状态）。 */
    public void restoreInvalidStreak(int invalidStreak) {
        this.invalidStreak = Math.max(0, invalidStreak);
    }

    /** 占位建会话：暂存分析输入原文并标记待回填（后台与首轮兜底分析共用这两份原文）。 */
    public void markAnalysisPending(String resumeText, String jdText) {
        this.resumeText = resumeText;
        this.jdText = jdText;
        this.analysisPending = true;
    }

    /** 分析完成回填真值并清占位：原文同时清空，存储层 completeAnalysis 的内存镜像。 */
    public void applyAnalysis(String resumeDigest, JdProfile jdProfile) {
        this.resumeDigest = resumeDigest;
        this.jdProfile = jdProfile;
        this.analysisPending = false;
        this.resumeText = null;
        this.jdText = null;
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

    public int invalidStreak() {
        return invalidStreak;
    }

    public List<String> askedQuestions() {
        return List.copyOf(askedQuestions);
    }

    /** 分析是否仍为占位：true 时摘要与画像不可信，需等待回填或补跑。 */
    public boolean analysisPending() {
        return analysisPending;
    }

    /** 分析输入原文（仅 pending 期间非空），供后台与首轮兜底分析读取。 */
    public String resumeText() {
        return resumeText;
    }

    public String jdText() {
        return jdText;
    }
}
