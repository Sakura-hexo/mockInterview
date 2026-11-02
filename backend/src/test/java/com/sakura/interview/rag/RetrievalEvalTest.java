package com.sakura.interview.rag;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class RetrievalEvalTest {

    private static SearchHit hit(String id, float score) {
        return new SearchHit(id, "题干" + id, "标签", "来源", score);
    }

    /** 假检索：按查询返回固定命中序列，模拟正排与失手两种形态。 */
    private static List<SearchHit> fakeSearch(Map<String, List<SearchHit>> table, String query) {
        return table.getOrDefault(query, List.of());
    }

    @Test
    void 命中与指标计算及形态分组() {
        List<EvalCase> cases = List.of(
                new EvalCase("E-1", "q1", List.of("QB-1"), "术语直查", null),
                new EvalCase("E-2", "q2", List.of("QB-2"), "术语直查", null),
                new EvalCase("E-3", "q3", List.of("QB-3"), "口语化", null));
        Map<String, List<SearchHit>> table = Map.of(
                "q1", List.of(hit("QB-1", 0.9f)),
                "q2", List.of(hit("X-1", 0.8f), hit("X-2", 0.7f), hit("QB-2", 0.6f)),
                "q3", List.of(hit("Y-1", 0.5f)));

        List<RetrievalEval.TierReport> reports = RetrievalEval.run(q -> fakeSearch(table, q), cases);

        assertThat(reports).hasSize(2);
        RetrievalEval.TierReport direct = reports.get(0);
        assertThat(direct.tier()).isEqualTo("术语直查");
        assertThat(direct.total()).isEqualTo(2);
        assertThat(direct.hit1()).isEqualTo(1);
        assertThat(direct.hit3()).isEqualTo(2);
        // MRR = (1/1 + 1/3) / 2
        assertThat(direct.mrr()).isEqualTo((1.0 + 1.0 / 3) / 2, org.assertj.core.data.Offset.offset(1e-9));
        assertThat(direct.failures()).isEmpty();

        RetrievalEval.TierReport colloquial = reports.get(1);
        assertThat(colloquial.hit1()).isZero();
        assertThat(colloquial.failures()).hasSize(1);
        assertThat(colloquial.failures().get(0).evalCase().id()).isEqualTo("E-3");
    }

    @Test
    void 多金标命中任一即算与负例永不命中() {
        List<EvalCase> cases = List.of(
                new EvalCase("E-1", "q1", List.of("MY-7", "RD-8"), "口语化", null),
                new EvalCase("E-2", "q2", List.of(), "题库外", null));
        Map<String, List<SearchHit>> table = Map.of(
                "q1", List.of(hit("RD-8", 0.8f), hit("X-1", 0.7f)),
                "q2", List.of(hit("X-1", 0.4f)));

        List<RetrievalEval.TierReport> reports = RetrievalEval.run(q -> fakeSearch(table, q), cases);

        assertThat(reports.get(0).tier()).isEqualTo("口语化");
        assertThat(reports.get(0).hit1()).isEqualTo(1);
        assertThat(reports.get(0).failures()).isEmpty();
        assertThat(reports.get(1).tier()).isEqualTo("题库外");
        assertThat(reports.get(1).hit1()).isZero();
        assertThat(reports.get(1).failures()).hasSize(1);
    }
}
