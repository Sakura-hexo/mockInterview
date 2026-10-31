package com.sakura.interview.session;

import java.util.ArrayList;
import java.util.List;

import com.sakura.interview.tool.JdProfile;

/**
 * 会话状态：常驻层元数据（简历摘要、岗位要点、已问题目清单、轮次）。
 * 每轮从存储层加载、变更后写回，进程重启可恢复。
 * askedQuestions 存面试官每轮输出摘要注入下一轮系统提示词防止重复出题；
 * 单条与总量都设上限，更早的题目由滚动前情摘要覆盖。
 */
public final class SessionState {

    static final int ASKED_SUMMARY_MAX_CHARS = 60;
    static final int ASKED_KEEP_MAX = 40;

    private final String sessionId;
    private final String resumeDigest;
    private final JdProfile jdProfile;
    private final List<String> askedQuestions = new ArrayList<>();
    private int round;

    public SessionState(String sessionId, String resumeDigest, JdProfile jdProfile) {
        this(sessionId, resumeDigest, jdProfile, List.of(), 0);
    }

    /** 恢复构造：从存储层回读已问题目清单与轮次。 */
    public SessionState(String sessionId, String resumeDigest, JdProfile jdProfile,
                        List<String> askedQuestions, int round) {
        this.sessionId = sessionId;
        this.resumeDigest = resumeDigest;
        this.jdProfile = jdProfile;
        this.askedQuestions.addAll(askedQuestions);
        this.round = round;
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

    public String sessionId() {
        return sessionId;
    }

    public String resumeDigest() {
        return resumeDigest;
    }

    public JdProfile jdProfile() {
        return jdProfile;
    }

    public int round() {
        return round;
    }

    public List<String> askedQuestions() {
        return List.copyOf(askedQuestions);
    }
}
