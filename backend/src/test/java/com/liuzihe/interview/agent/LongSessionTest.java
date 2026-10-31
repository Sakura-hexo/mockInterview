package com.liuzihe.interview.agent;

import com.liuzihe.interview.infra.LlmCaller;
import com.liuzihe.interview.infra.StructuredOutputInvoker;
import com.liuzihe.interview.session.ContextAssembler;
import com.liuzihe.interview.session.ContextCompressor;
import com.liuzihe.interview.session.FakeSessionStore;
import com.liuzihe.interview.session.SessionSummary;
import com.liuzihe.interview.session.StoredMessage;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 一小时对话量的零成本等价证明：50 轮模拟对话（假模型）断言
 * 每轮组装输入不超预算、早期内容进摘要不丢失、token 已记录、重启后无缝衔接。
 */
class LongSessionTest {

    private static final String JD_JSON =
            "{\"direction\":\"Java后端\",\"skills\":[\"Spring\",\"MySQL\"],\"bonus\":[]}";

    private static LlmCaller branchingCaller() {
        return (sys, user) -> {
            if (sys.contains("简历分析器")) {
                return "{\"digest\":\"三年 Java 后端，主导订单系统\"}";
            }
            if (sys.contains("意图分类器")) {
                return "{\"intent\":\"INTERVIEW\"}";
            }
            return JD_JSON;
        };
    }

    private static String answer(int round) {
        return "候选人第" + round + "轮回答，细节是" + "高并发场景下的处理方案".repeat(40);
    }

    @Test
    void 五十轮输入预算平稳早期内容进摘要() {
        AtomicInteger questionNo = new AtomicInteger();
        FakeAgentRuntime runtime = new FakeAgentRuntime(
                c -> "面试官问题" + questionNo.incrementAndGet() + "：请谈谈Redis持久化");
        FakeSessionStore store = new FakeSessionStore();
        Supervisor supervisor = new Supervisor(runtime, new StructuredOutputInvoker(branchingCaller()),
                store, new ContextCompressor((sys, user) -> "前情：候选人依次谈了并发、JVM、Redis等话题"));
        String sessionId = supervisor.createSession("我的简历", "招 Java 后端").sessionId();

        for (int round = 1; round <= 50; round++) {
            supervisor.chat(sessionId, answer(round));
        }

        runtime.calls().forEach(call ->
                assertThat(call.userInput().length())
                        .isLessThanOrEqualTo(ContextAssembler.MAX_CONTEXT_CHARS));
        String last = runtime.calls().get(49).userInput();
        assertThat(last)
                .contains("候选人第50轮回答")
                .contains("面试官问题49")
                .contains("【前情摘要】")
                .doesNotContain("候选人第1轮回答")
                .doesNotContain("面试官问题1：请谈谈Redis持久化");

        List<StoredMessage> all = store.messages.get(sessionId);
        assertThat(all).hasSize(100);
        assertThat(all.get(99).inputTokens()).isGreaterThan(0);
        assertThat(all.get(99).outputTokens()).isEqualTo(10);

        SessionSummary summary = store.summaries.get(sessionId);
        assertThat(summary).isNotNull();
        assertThat(summary.coveredSeq()).isGreaterThanOrEqualTo(60);
    }

    @Test
    void 换新实例恢复继续无缝衔接() {
        FakeSessionStore store = new FakeSessionStore();
        AtomicInteger qNo = new AtomicInteger();
        FakeAgentRuntime first = new FakeAgentRuntime(c -> "面试官问题" + qNo.incrementAndGet());
        Supervisor previous = new Supervisor(first, new StructuredOutputInvoker(branchingCaller()),
                store, new ContextCompressor((sys, user) -> "前情：谈过并发"));
        String sessionId = previous.createSession("我的简历", "招 Java 后端").sessionId();
        for (int round = 1; round <= 25; round++) {
            previous.chat(sessionId, answer(round));
        }

        AtomicInteger qNo2 = new AtomicInteger(25);
        FakeAgentRuntime second = new FakeAgentRuntime(c -> "面试官问题" + qNo2.incrementAndGet());
        Supervisor resumed = new Supervisor(second, new StructuredOutputInvoker(branchingCaller()),
                store, new ContextCompressor((sys, user) -> "前情：谈过并发"));

        Supervisor.SupervisorReply reply = resumed.chat(sessionId, answer(26));

        assertThat(reply.intent()).isEqualTo(Intent.INTERVIEW);
        String input = second.calls().get(0).userInput();
        assertThat(input)
                .contains("候选人第26轮回答")
                .contains("面试官问题25")
                .contains("【前情摘要】");
        assertThat(resumed.state(sessionId).round()).isEqualTo(26);
    }
}
