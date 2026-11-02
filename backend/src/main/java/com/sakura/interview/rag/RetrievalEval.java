package com.sakura.interview.rag;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

/**
 * 检索评测：按形态分组计算 hit@1、hit@3 与 MRR。
 * 检索经函数注入——生产口径传混合检索，上限对照传改写融合口径，单测传假实现。
 * 题库外负例的期望为空，永远不算命中；其 top 命中明细留给调用方做阈值定标。
 */
public final class RetrievalEval {

    /** 单条结果：命中排名为零表示未进 topK（负例恒为零）。 */
    public record CaseResult(EvalCase evalCase, List<SearchHit> top, int firstGoldRank) {

        public boolean hitAt(int k) {
            return firstGoldRank > 0 && firstGoldRank <= k;
        }
    }

    /** 单形态汇总：命中共指标、失败明细与全量结果（--scores 定标数据）。 */
    public record TierReport(String tier, int total, int hit1, int hit3, double mrr,
                             List<CaseResult> failures, List<CaseResult> results) {
    }

    private RetrievalEval() {
    }

    public static List<TierReport> run(Function<String, List<SearchHit>> search,
                                       List<EvalCase> cases) {
        Map<String, List<CaseResult>> byTier = new LinkedHashMap<>();
        for (EvalCase evalCase : cases) {
            List<SearchHit> top = search.apply(evalCase.query());
            byTier.computeIfAbsent(evalCase.tier(), t -> new ArrayList<>())
                    .add(new CaseResult(evalCase, top, firstGoldRank(evalCase, top)));
        }
        List<TierReport> reports = new ArrayList<>();
        for (Map.Entry<String, List<CaseResult>> entry : byTier.entrySet()) {
            List<CaseResult> results = entry.getValue();
            int hit1 = 0;
            int hit3 = 0;
            double reciprocalSum = 0;
            List<CaseResult> failures = new ArrayList<>();
            for (CaseResult result : results) {
                if (result.hitAt(1)) {
                    hit1++;
                }
                if (result.hitAt(3)) {
                    hit3++;
                } else {
                    failures.add(result);
                }
                reciprocalSum += result.firstGoldRank() > 0
                        ? 1.0 / result.firstGoldRank() : 0;
            }
            reports.add(new TierReport(entry.getKey(), results.size(), hit1, hit3,
                    reciprocalSum / results.size(), failures, results));
        }
        return reports;
    }

    private static int firstGoldRank(EvalCase evalCase, List<SearchHit> top) {
        for (int i = 0; i < top.size(); i++) {
            if (evalCase.goldIds().contains(top.get(i).id())) {
                return i + 1;
            }
        }
        return 0;
    }
}
