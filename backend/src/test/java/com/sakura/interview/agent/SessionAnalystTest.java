package com.sakura.interview.agent;

import com.sakura.interview.infra.LlmCaller;
import com.sakura.interview.infra.StructuredOutputInvoker;
import com.sakura.interview.session.AnalysisCache;
import com.sakura.interview.session.FakeAnalysisCache;
import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class SessionAnalystTest {

    private static final String FULL_JSON =
            "{\"digest\":\"三年 Java 后端，主导订单系统\",\"direction\":\"Java后端\","
                    + "\"skills\":[\"Spring\",\"MySQL\"],\"bonus\":[\"Kafka\"]}";

    @Test
    void 一次调用同时产出摘要与岗位画像并落缓存() {
        AtomicInteger calls = new AtomicInteger();
        LlmCaller caller = (sys, user) -> {
            calls.incrementAndGet();
            assertThat(sys).contains("创建分析器");
            assertThat(user).startsWith("【简历】\n我的简历").contains("招 Java 后端");
            return FULL_JSON;
        };
        FakeAnalysisCache cache = new FakeAnalysisCache();
        SessionAnalyst.Analysis analysis = new SessionAnalyst(
                new StructuredOutputInvoker(caller), cache).analyze(" 我的简历 ", " 招 Java 后端 ");

        assertThat(analysis.resumeDigest()).contains("三年 Java 后端");
        assertThat(analysis.jdProfile().direction()).isEqualTo("Java后端");
        assertThat(analysis.jdProfile().skills()).containsExactly("Spring", "MySQL");
        assertThat(analysis.jdProfile().bonus()).containsExactly("Kafka");
        assertThat(calls.get()).isEqualTo(1);
        assertThat(cache.saved).hasSize(1);
    }

    @Test
    void 缓存命中不再发模型调用() {
        AtomicInteger calls = new AtomicInteger();
        SessionAnalyst analyst = new SessionAnalyst(
                new StructuredOutputInvoker((sys, user) -> {
                    calls.incrementAndGet();
                    return FULL_JSON;
                }), new FakeAnalysisCache());

        SessionAnalyst.Analysis first = analyst.analyze("我的简历", "招 Java 后端");
        SessionAnalyst.Analysis second = analyst.analyze("我的简历", "招 Java 后端");

        assertThat(calls.get()).isEqualTo(1);
        assertThat(second.resumeDigest()).isEqualTo(first.resumeDigest());
        assertThat(second.jdProfile()).isEqualTo(first.jdProfile());
    }

    @Test
    void skills缺失时JD降级摘要保留且不落缓存() {
        FakeAnalysisCache cache = new FakeAnalysisCache();
        SessionAnalyst.Analysis analysis = new SessionAnalyst(
                new StructuredOutputInvoker((sys, user) ->
                        "{\"digest\":\"三年 Java 后端\",\"skills\":[]}"), cache)
                .analyze("我的简历", "招 Java 后端");

        assertThat(analysis.resumeDigest()).contains("三年 Java 后端");
        assertThat(analysis.jdProfile().direction()).isEqualTo("解析降级");
        assertThat(analysis.jdProfile().skills()).containsExactly("招 Java 后端");
        assertThat(cache.saved).isEmpty();
    }

    @Test
    void digest缺失回退占位文案且不落缓存() {
        FakeAnalysisCache cache = new FakeAnalysisCache();
        SessionAnalyst.Analysis analysis = new SessionAnalyst(
                new StructuredOutputInvoker((sys, user) ->
                        "{\"direction\":\"Java后端\",\"skills\":[\"Spring\"]}"), cache)
                .analyze("我的简历", "招 Java 后端");

        assertThat(analysis.resumeDigest()).isEqualTo("简历解析未产出摘要");
        assertThat(analysis.jdProfile().skills()).containsExactly("Spring");
        assertThat(cache.saved).isEmpty();
    }

    @Test
    void 缓存内容损坏按未命中重新调用() {
        FakeAnalysisCache cache = new FakeAnalysisCache();
        cache.saved.put(AnalysisCache.keyOf("我的简历", "招 Java 后端"), "{{{损坏");
        AtomicInteger calls = new AtomicInteger();

        SessionAnalyst.Analysis analysis = new SessionAnalyst(
                new StructuredOutputInvoker((sys, user) -> {
                    calls.incrementAndGet();
                    return FULL_JSON;
                }), cache).analyze("我的简历", "招 Java 后端");

        assertThat(calls.get()).isEqualTo(1);
        assertThat(analysis.resumeDigest()).contains("三年 Java 后端");
        assertThat(cache.saved).containsValue(FULL_JSON);
    }

    @Test
    void JD为空或超限直接拒绝不调模型() {
        AtomicInteger calls = new AtomicInteger();
        SessionAnalyst analyst = new SessionAnalyst(
                new StructuredOutputInvoker((sys, user) -> {
                    calls.incrementAndGet();
                    return FULL_JSON;
                }), new FakeAnalysisCache());

        assertThatThrownBy(() -> analyst.analyze("我的简历", " "))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("JD 文本为空");
        assertThatThrownBy(() -> analyst.analyze("我的简历", "x".repeat(20 * 1024 + 1)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("20KB");
        assertThat(calls.get()).isZero();
    }
}
