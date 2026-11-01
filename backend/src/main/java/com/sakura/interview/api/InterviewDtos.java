package com.sakura.interview.api;

import com.sakura.interview.session.SessionConfig;
import com.sakura.interview.session.SessionOverview;

import java.util.List;

/**
 * 面试接口的请求与响应载体（接口文档第 3、4 节），字段名与前端 types.ts 一一对应。
 */
public final class InterviewDtos {

    private InterviewDtos() {
    }

    public record CreateRequest(String resumeText, String jdText, SessionConfig config) {
    }

    public record CreateResponse(String sessionId, String resumeDigest) {
    }

    public record StartResponse(String firstQuestion, List<String> focusPoints) {
    }

    /** images 为 P1 视觉输入预留（接口文档 4.3），当前不启用。 */
    public record ChatRequest(String content, List<String> images) {
    }

    public record ReportStatusResponse(String reportStatus) {
    }

    public record ListResponse(long total, List<SessionOverview> list) {
    }

    /** 消息视图：角色转大写口径，tokens 为该条消息输入输出合计，time 为秒级 ISO 时间戳。 */
    public record MessageView(String id, String role, String content, long tokens,
                              String time, String action) {
    }

    public record DetailResponse(SessionOverview session, List<MessageView> messages) {
    }
}
