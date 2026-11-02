package com.sakura.interview.rag;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 检索长度分档与双查询融合（步骤 2c）。
 * RRF 融合分是各路排名倒数的和，绝对数值不可跨查询比较。
 * 步骤 9 已用 45 条评测集定标验证：top1 加 top2 分差与 top1 绝对分均无法分离
 * 题库外负例——负例可双路同现拿高分、正例可单路弱命中分差为零，
 * 简单阈值误判率不可接受，故不设分数阈值；题库外兜底由面试官提示词的
 * 候选题不合适自行出题承担。稠密分阈值（负例密集区约 0.46 到 0.54）
 * 是后续题库扩容后可重评的候选信号。
 */
public final class SearchTiers {

    public static final int SHORT_QUERY_MAX_CHARS = 12;
    public static final int SHORT_TOPK = 3;
    public static final int LONG_TOPK = 5;

    private SearchTiers() {
    }

    /** 按查询长度分档：短查询少而精，长查询多召回。 */
    public static int topK(String query) {
        return query.strip().length() <= SHORT_QUERY_MAX_CHARS ? SHORT_TOPK : LONG_TOPK;
    }

    /** 原始与改写双查询的命中合并：同题取高分，按分数取前 topK。 */
    public static List<SearchHit> fuse(List<SearchHit> left, List<SearchHit> right, int topK) {
        Map<String, SearchHit> merged = new HashMap<>();
        for (SearchHit hit : left) {
            merged.merge(hit.id(), hit, SearchTiers::better);
        }
        for (SearchHit hit : right) {
            merged.merge(hit.id(), hit, SearchTiers::better);
        }
        return merged.values().stream()
                .sorted((a, b) -> Float.compare(b.score(), a.score()))
                .limit(topK)
                .toList();
    }

    private static SearchHit better(SearchHit a, SearchHit b) {
        return a.score() >= b.score() ? a : b;
    }
}
