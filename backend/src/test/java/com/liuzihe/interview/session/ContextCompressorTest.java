package com.liuzihe.interview.session;

import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

class ContextCompressorTest {

    private static void appendMessages(FakeSessionStore store, int count) {
        for (int i = 1; i <= count; i++) {
            store.appendMessage("s", i % 2 == 1 ? "user" : "interviewer", "消息" + i, i, i);
        }
    }

    @Test
    void 未满一批不触发摘要() {
        FakeSessionStore store = new FakeSessionStore();
        appendMessages(store, 25);
        AtomicInteger calls = new AtomicInteger();
        ContextCompressor compressor = new ContextCompressor((sys, user) -> {
            calls.incrementAndGet();
            return "摘要";
        });

        compressor.compressIfNeeded(store, "s");

        assertThat(calls.get()).isZero();
        assertThat(store.loadSummary("s")).isEmpty();
    }

    @Test
    void 满一批触发合并并推进边界() {
        FakeSessionStore store = new FakeSessionStore();
        appendMessages(store, 31);
        AtomicInteger calls = new AtomicInteger();
        ContextCompressor compressor = new ContextCompressor((sys, user) -> {
            calls.incrementAndGet();
            assertThat(user).contains("（暂无）").contains("消息1").contains("消息10")
                    .doesNotContain("消息11");
            return "新摘要";
        });

        compressor.compressIfNeeded(store, "s");

        assertThat(calls.get()).isEqualTo(1);
        SessionSummary summary = store.loadSummary("s").orElseThrow();
        assertThat(summary.coveredSeq()).isEqualTo(10);
        assertThat(summary.text()).isEqualTo("新摘要");
    }

    @Test
    void 每次调用最多推进一批() {
        FakeSessionStore store = new FakeSessionStore();
        appendMessages(store, 51);
        ContextCompressor compressor = new ContextCompressor((sys, user) -> "摘要");

        compressor.compressIfNeeded(store, "s");
        assertThat(store.loadSummary("s").orElseThrow().coveredSeq()).isEqualTo(10);

        compressor.compressIfNeeded(store, "s");
        assertThat(store.loadSummary("s").orElseThrow().coveredSeq()).isEqualTo(20);
    }

    @Test
    void 摘要失败降级不回退全量() {
        FakeSessionStore store = new FakeSessionStore();
        appendMessages(store, 31);
        store.saveSummary("s", "旧摘要文本", 0);
        ContextCompressor compressor = new ContextCompressor((sys, user) -> {
            throw new IllegalStateException("模型超时");
        });

        compressor.compressIfNeeded(store, "s");

        SessionSummary summary = store.loadSummary("s").orElseThrow();
        assertThat(summary.text()).isEqualTo("旧摘要文本");
        assertThat(summary.coveredSeq()).isZero();
    }

    @Test
    void 空白返回不落库() {
        FakeSessionStore store = new FakeSessionStore();
        appendMessages(store, 31);
        ContextCompressor compressor = new ContextCompressor((sys, user) -> "   ");

        compressor.compressIfNeeded(store, "s");

        assertThat(store.loadSummary("s")).isEmpty();
    }
}
