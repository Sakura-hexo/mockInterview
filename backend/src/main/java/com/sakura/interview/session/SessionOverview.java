package com.sakura.interview.session;

/**
 * 会话概览：历史列表与会话详情头部共用的轻量视图，
 * 不携带简历摘要与消息等重负载（接口文档第 3 节 InterviewSummary）。
 */
public record SessionOverview(String sessionId, String status, String direction,
                              String difficulty, int questionCount, String createTime) {
}
