package com.sakura.interview.api;

import java.util.List;

/**
 * 用量统计端口：token 消耗按消息口径聚合（事件时间分桶，见实现注释），
 * 生产实现为 MySQL（infra），单测用内存实现。
 */
public interface StatsStore {

    /** 单用户汇总：总输入/输出/合计 token、会话数、消息数。 */
    TokenSummary userSummary(long userId);

    /** 全站汇总（含无归属会话，如 CLI 建的）。 */
    TokenSummary siteSummary();

    /** 近 N 天每日 token：只返回有数据的天，补零归展示层。 */
    List<DailyPoint> dailyTrend(long userId, int days);

    /** 用户最近 N 场会话的 token 明细，按创建时间倒序。 */
    List<SessionRow> recentSessions(long userId, int limit);

    /** 全站按用户聚合（token 倒序）：管理页签的用户列表。 */
    List<UserRow> topUsers(int limit);

    record TokenSummary(long inputTokens, long outputTokens, int sessionCount, int messageCount) {
        public long totalTokens() {
            return inputTokens + outputTokens;
        }
    }

    record DailyPoint(String date, long tokens) {
    }

    record SessionRow(String sessionId, String direction, String difficulty,
                      String createdAt, int messages, long tokens) {
    }

    record UserRow(String username, int sessions, long tokens) {
    }
}
