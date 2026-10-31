package com.liuzihe.interview.session;

import com.liuzihe.interview.infra.LlmCaller;
import com.liuzihe.interview.infra.StructuredOutputInvoker;
import com.liuzihe.interview.tool.JdProfile;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Predicate;

import static org.assertj.core.api.Assertions.assertThat;

class ReportGeneratorTest {

    private static final String JD_JSON =
            "{\"direction\":\"Java后端\",\"skills\":[\"Spring\"],\"bonus\":[]}";

    private static StoredMessage msg(int seq, String role, String text) {
        return new StoredMessage(seq, role, text, seq, seq);
    }

    /** 评估调用器：批评估按批内题目数产出固定分；汇总返回固定四维；可注入毒药条件。 */
    private static LlmCaller evalCaller(AtomicInteger batchCalls, Predicate<String> poison) {
        return (sys, user) -> {
            if (sys.contains("逐题评估器")) {
                batchCalls.incrementAndGet();
                if (poison.test(user)) {
                    throw new IllegalStateException("模型超时");
                }
                int questions = user.split("【第", -1).length - 1;
                StringBuilder sb = new StringBuilder("{\"items\":[");
                for (int i = 0; i < questions; i++) {
                    sb.append(i > 0 ? "," : "")
                            .append("{\"question\":\"题\",\"userAnswerSummary\":\"答\",\"comment\":\"扎实\",\"score\":7}");
                }
                return sb.append("]}").toString();
            }
            if (sys.contains("复盘汇总器")) {
                return "{\"scores\":{\"overall\":7,\"technicalDepth\":7,\"expression\":7,\"followUpHandling\":7},"
                        + "\"suggestions\":[\"建议一\",\"建议二\"]}";
            }
            return JD_JSON;
        };
    }

    private static ReportGenerator generator(LlmCaller caller, FakeSessionStore store) {
        return new ReportGenerator(new StructuredOutputInvoker(caller), store);
    }

    private static FakeSessionStore sessionWithPairs(int pairCount) {
        FakeSessionStore store = new FakeSessionStore();
        store.sessions.put("s", new SessionState("s", "三年 Java 后端",
                new JdProfile("Java后端", List.of("Spring"), List.of())));
        for (int i = 1; i <= pairCount; i++) {
            store.appendMessage("s", "interviewer", "第" + i + "题是什么", 10, 10);
            store.appendMessage("s", "user", "第" + i + "题的回答内容", 0, 0);
        }
        return store;
    }

    @Test
    void 配对规则_闲聊跳过_悬空回答并入_自我介绍不计题() {
        List<QaPair> pairs = ReportGenerator.pairUp(List.of(
                msg(1, "user", "面试官好，我自我介绍"),
                msg(2, "interviewer", "第一题"),
                msg(3, "user", "回答A1"),
                msg(4, "user", "补充A1"),
                msg(5, "chat", "天气不错加油"),
                msg(6, "interviewer", "第二题"),
                msg(7, "user", "回答A2"),
                msg(8, "interviewer", "第三题")));

        assertThat(pairs).hasSize(3);
        assertThat(pairs.get(0).answer()).isEqualTo("回答A1\n补充A1");
        assertThat(pairs.get(1).question()).isEqualTo("第二题");
        assertThat(pairs.get(2).answer()).isEmpty();
    }

    @Test
    void 生成完整报告_逐题汇总与统计齐备() {
        FakeSessionStore store = sessionWithPairs(3);
        InterviewReport report = generator(
                evalCaller(new AtomicInteger(), user -> false), store).generate("s");

        assertThat(report.items()).hasSize(3);
        assertThat(report.scores().overall()).isEqualTo(7);
        assertThat(report.suggestions()).containsExactly("建议一", "建议二");
        assertThat(report.tokenTotal()).isEqualTo(
                store.messages.get("s").stream()
                        .mapToLong(m -> m.inputTokens() + m.outputTokens()).sum());
        assertThat(report.sessionId()).isEqualTo("s");
    }

    @Test
    void 二十题按八对分三批调用() {
        FakeSessionStore store = sessionWithPairs(20);
        AtomicInteger batchCalls = new AtomicInteger();

        generator(evalCaller(batchCalls, user -> false), store).generate("s");

        assertThat(batchCalls.get()).isEqualTo(3);
    }

    @Test
    void 批失败二分_半批成功半批显式降级() {
        FakeSessionStore store = sessionWithPairs(2);
        store.messages.put("s", new ArrayList<>(List.of(
                new StoredMessage(1, "interviewer", "毒药题", 1, 1),
                new StoredMessage(2, "user", "回答一", 1, 1),
                new StoredMessage(3, "interviewer", "正常题", 1, 1),
                new StoredMessage(4, "user", "回答二", 1, 1))));
        AtomicInteger batchCalls = new AtomicInteger();

        InterviewReport report = generator(
                evalCaller(batchCalls, user -> user.contains("毒药题")), store).generate("s");

        assertThat(report.items()).hasSize(2);
        assertThat(report.items()).anySatisfy(item -> {
            assertThat(item.score()).isZero();
            assertThat(item.comment()).contains("系统降级");
        });
        assertThat(report.items()).anySatisfy(item -> assertThat(item.score()).isEqualTo(7));
        // 调用器次数含 invoker 内部 3 次重试：初始批 3 + 毒药半批 3 + 正常半批首试即成 1
        assertThat(batchCalls.get()).isEqualTo(7);
    }

    @Test
    void 二分预算耗尽_整体降级不伪装() {
        FakeSessionStore store = sessionWithPairs(20);
        AtomicInteger batchCalls = new AtomicInteger();

        InterviewReport report = generator(
                evalCaller(batchCalls, user -> true), store).generate("s");

        assertThat(report.items()).hasSize(20);
        assertThat(report.items()).allSatisfy(item -> {
            assertThat(item.score()).isZero();
            assertThat(item.comment()).contains("系统降级");
        });
        // 调用器次数含 invoker 内部 3 次重试：三个初始批各 3 次（9），
        // 前两批各消耗 2 次二分重试共 4 次invokeBatch（12），第三批预算耗尽不再二分
        assertThat(batchCalls.get()).isEqualTo(9 + 4 * 3);
    }

    @Test
    void 汇总失败降级为均值并注明() {
        FakeSessionStore store = sessionWithPairs(2);
        AtomicInteger batchCalls = new AtomicInteger();

        InterviewReport report = generator((sys, user) -> {
            if (sys.contains("逐题评估器")) {
                return "{\"items\":[{\"question\":\"题\",\"userAnswerSummary\":\"答\",\"comment\":\"扎实\",\"score\":8}]}";
            }
            throw new IllegalStateException("汇总通道故障");
        }, store).generate("s");

        assertThat(report.scores().overall()).isEqualTo(8);
        assertThat(report.suggestions().get(0)).contains("系统降级");
    }

    @Test
    void 超长文本首尾压缩进提示词() {
        FakeSessionStore store = sessionWithPairs(1);
        String huge = "字".repeat(3000);
        store.messages.put("s", new ArrayList<>(List.of(
                new StoredMessage(1, "interviewer", "题" + huge, 1, 1),
                new StoredMessage(2, "user", "答" + huge, 1, 1))));
        List<String> captured = new ArrayList<>();

        generator((sys, user) -> {
            if (sys.contains("逐题评估器")) {
                captured.add(user);
                return "{\"items\":[{\"question\":\"题\",\"userAnswerSummary\":\"答\",\"comment\":\"好\",\"score\":7}]}";
            }
            return "{\"scores\":{\"overall\":7,\"technicalDepth\":7,\"expression\":7,\"followUpHandling\":7},"
                    + "\"suggestions\":[\"建议\"]}";
        }, store).generate("s");

        String prompt = captured.get(0);
        assertThat(prompt).contains("中间内容已压缩").doesNotContain("答" + "字".repeat(2000));
    }
}
