package com.sakura.interview.agent;

import com.sakura.interview.tool.JdProfile;

import java.util.ArrayList;
import java.util.List;

/**
 * 内存版会话状态：步骤 4 编排自用，步骤 5 换 Redis 与 MySQL 持久化实现，字段届时对齐。
 * askedQuestions 存面试官每轮输出摘要，注入下一轮系统提示词防止重复出题。
 */
public final class InMemorySessionState {

    private static final int ASKED_SUMMARY_MAX_CHARS = 100;

    private final String sessionId;
    private final String resumeDigest;
    private final JdProfile jdProfile;
    private final List<String> askedQuestions = new ArrayList<>();
    private int round;

    public InMemorySessionState(String sessionId, String resumeDigest, JdProfile jdProfile) {
        this.sessionId = sessionId;
        this.resumeDigest = resumeDigest;
        this.jdProfile = jdProfile;
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

    /** 记录面试官本轮输出为已问题目摘要并推进轮次。 */
    public void recordAsked(String interviewerReply) {
        String summary = interviewerReply == null ? "" : interviewerReply.strip();
        if (summary.length() > ASKED_SUMMARY_MAX_CHARS) {
            summary = summary.substring(0, ASKED_SUMMARY_MAX_CHARS) + "…";
        }
        if (!summary.isEmpty()) {
            askedQuestions.add(summary);
        }
        round++;
    }

    public List<String> askedQuestions() {
        return List.copyOf(askedQuestions);
    }
}
