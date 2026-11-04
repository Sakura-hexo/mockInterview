package com.sakura.interview.api;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 统计单测用内存实现：数据可预置，查询按 userId 过滤，语义与 MySQL 实现对齐。
 */
public final class FakeStatsStore implements StatsStore {

    public final Map<Long, TokenSummary> summaries = new HashMap<>();
    public final Map<Long, List<DailyPoint>> trends = new HashMap<>();
    public final Map<Long, List<SessionRow>> sessionsByUser = new HashMap<>();
    public TokenSummary site = new TokenSummary(0, 0, 0, 0);
    public final List<UserRow> users = new ArrayList<>();

    @Override
    public TokenSummary userSummary(long userId) {
        return summaries.getOrDefault(userId, new TokenSummary(0, 0, 0, 0));
    }

    @Override
    public TokenSummary siteSummary() {
        return site;
    }

    @Override
    public List<DailyPoint> dailyTrend(long userId, int days) {
        return trends.getOrDefault(userId, List.of());
    }

    @Override
    public List<SessionRow> recentSessions(long userId, int limit) {
        return sessionsByUser.getOrDefault(userId, List.of());
    }

    @Override
    public List<UserRow> topUsers(int limit) {
        return users;
    }
}
