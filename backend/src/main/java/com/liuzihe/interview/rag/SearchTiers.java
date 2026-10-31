package com.liuzihe.interview.rag;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 检索长度分档与双查询融合（步骤 2c）。
 * RRF 融合分是各路排名倒数的和，绝对数值不可跨查询比较，
 * 因此分档只调 topK 不设分数阈值，阈值思路留给步骤 9 评测时用命中率验证。
 */
public final class SearchTiers {

    static final int SHORT_QUERY_MAX_CHARS = 12;
    static final int SHORT_TOPK = 3;
    static final int LONG_TOPK = 5;

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
