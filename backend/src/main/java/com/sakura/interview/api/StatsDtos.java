package com.sakura.interview.api;

/** 用量统计与管理端的响应结构。 */
final class StatsDtos {

    private StatsDtos() {
    }

    /** 展平的汇总视图：totalTokens 是计算值，直接做 DTO 组件确保序列化进 JSON。 */
    record SummaryView(long inputTokens, long outputTokens, long totalTokens,
                       int sessionCount, int messageCount) {
        static SummaryView from(StatsStore.TokenSummary summary) {
            return new SummaryView(summary.inputTokens(), summary.outputTokens(),
                    summary.totalTokens(), summary.sessionCount(), summary.messageCount());
        }
    }

    record TokenStatsResponse(SummaryView summary,
                              java.util.List<StatsStore.DailyPoint> daily,
                              java.util.List<StatsStore.SessionRow> sessions) {
    }

    record AdminOverviewResponse(SummaryView site,
                                 java.util.List<StatsStore.UserRow> users) {
    }

    /** 解析 N 题、入库 M 题、跳过 N-M 题（题号已存在）。 */
    record BankUploadResponse(int parsed, int imported, int skipped) {
    }
}
