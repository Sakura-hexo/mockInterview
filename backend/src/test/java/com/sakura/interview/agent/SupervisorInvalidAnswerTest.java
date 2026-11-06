package com.sakura.interview.agent;

import com.sakura.interview.infra.LlmCaller;
import com.sakura.interview.infra.StructuredOutputInvoker;
import com.sakura.interview.session.ContextCompressor;
import com.sakura.interview.session.FakeReportStore;
import com.sakura.interview.session.FakeSessionStore;
import com.sakura.interview.session.ReportService;
import com.sakura.interview.session.SessionState;
import com.sakura.interview.session.StoredMessage;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 无效作答三振出局（2026-11-06 方案）：模型按「选题 无效」标记判定、系统确定性计数执法。
 * 覆盖：标记剥除与连击、切题清零、警告与最终指令注入、三振收场、违规出新题模板桥接、
 * 提前收尾闸门豁免、拒绝轮连击中性、最终指令轮切题照常继续。
 */
class SupervisorInvalidAnswerTest {

    private static final String INVALID_TURN =
            "这个回答与题目无关。下一题：谈谈 JVM 内存结构。\n选题 无效";

    /** 按系统提示词分流：创建分析产出摘要画像、意图分类一律面试。 */
    private static LlmCaller branchingCaller() {
        return (sys, user) -> {
            if (sys.contains("创建分析器")) {
                return "{\"digest\":\"三年 Java 后端\",\"direction\":\"Java后端\","
                        + "\"skills\":[\"Spring\"],\"bonus\":[]}";
            }
            if (sys.contains("意图分类器")) {
                return "{\"intent\":\"INTERVIEW\"}";
            }
            return "{}";
        };
    }

    private record Wiring(Supervisor supervisor, FakeSessionStore sessions) {
    }

    private static Wiring newSupervisor(FakeAgentRuntime runtime) {
        FakeSessionStore sessions = new FakeSessionStore();
        StructuredOutputInvoker invoker = new StructuredOutputInvoker(branchingCaller());
        Supervisor supervisor = new Supervisor(runtime, invoker, sessions,
                new ContextCompressor((sys, user) -> "合并摘要"),
                new ReportService(sessions, new FakeReportStore()));
        return new Wiring(supervisor, sessions);
    }

    private static String createSession(Wiring wiring) {
        return wiring.supervisor().createSession("我的简历", "招 Java 后端").sessionId();
    }

    @Test
    void 无效标记剥除且连击计一振() {
        FakeAgentRuntime runtime = new FakeAgentRuntime(c -> INVALID_TURN);
        Wiring wiring = newSupervisor(runtime);
        String sessionId = createSession(wiring);

        Supervisor.SupervisorReply reply = wiring.supervisor().chat(sessionId, "我今天中午吃了火锅");

        assertThat(reply.action()).isEqualTo(Supervisor.ACTION_QUESTION);
        List<StoredMessage> messages = wiring.sessions().readAll(sessionId);
        // 落库与回显均不带标记：选题与无效一并剥除
        assertThat(messages.get(1).text())
                .doesNotContain("选题").doesNotContain("无效");
        assertThat(wiring.supervisor().state(sessionId).invalidStreak()).isEqualTo(1);
    }

    @Test
    void 无效标记在回复中部_仍判无效并全量剥除() {
        // 真机 s-adabc26c 抓到的自然输出形状：指出后先写「选题 无效」行，新题结尾再补裸选题
        FakeAgentRuntime runtime = new FakeAgentRuntime(c ->
                "答非所问，上一轮要求的是自我介绍。\n选题 无效\n你简历提到支付网关。"
                        + "下一题：MySQL 可重复读如何解决幻读。\n选题");
        Wiring wiring = newSupervisor(runtime);
        String sessionId = createSession(wiring);

        Supervisor.SupervisorReply reply =
                wiring.supervisor().chat(sessionId, "HashMap 底层是数组加链表加红黑树");

        assertThat(wiring.supervisor().state(sessionId).invalidStreak()).isEqualTo(1);
        assertThat(reply.action()).isEqualTo(Supervisor.ACTION_QUESTION);
        List<StoredMessage> messages = wiring.sessions().readAll(sessionId);
        assertThat(messages.get(1).text())
                .doesNotContain("选题").doesNotContain("无效")
                .contains("可重复读");
    }

    @Test
    void 切题回答清零连击并注入警告指令() {
        java.util.concurrent.atomic.AtomicInteger turn = new java.util.concurrent.atomic.AtomicInteger();
        FakeAgentRuntime runtime = new FakeAgentRuntime(c -> turn.incrementAndGet() == 2
                ? "理解到位。下一题：谈谈 MySQL 索引。\n选题" : INVALID_TURN);
        Wiring wiring = newSupervisor(runtime);
        String sessionId = createSession(wiring);

        wiring.supervisor().chat(sessionId, "胡话一");
        assertThat(wiring.supervisor().state(sessionId).invalidStreak()).isEqualTo(1);

        wiring.supervisor().chat(sessionId, "JVM 内存结构分为堆、栈、方法区");

        // 一振后的下一轮：注入连击状态行与警告指令（再无效将提前结束）
        assertThat(runtime.calls().get(1).systemPrompt())
                .contains("已连续无效作答 1 次")
                .contains("已判定无效作答")
                .contains("再出现一次无效作答");
        assertThat(wiring.supervisor().state(sessionId).invalidStreak()).isZero();
    }

    @Test
    void 三振收场_模型按指令结束_闸门豁免不重试() {
        FakeAgentRuntime runtime = new FakeAgentRuntime(c ->
                c.systemPrompt().contains("连续两轮无效作答")
                        ? "本场面试到此结束：因连续无效作答提前结束，请在结束后生成复盘报告。\n无效"
                        : INVALID_TURN);
        Wiring wiring = newSupervisor(runtime);
        String sessionId = createSession(wiring);

        wiring.supervisor().chat(sessionId, "胡话一");
        wiring.supervisor().chat(sessionId, "胡话二");
        Supervisor.SupervisorReply reply = wiring.supervisor().chat(sessionId, "胡话三");

        // 收场话术落库无标记残留、计追问型不占题数、会话 ENDED
        assertThat(reply.reply()).contains("提前结束");
        assertThat(reply.action()).isEqualTo(Supervisor.ACTION_FOLLOW_UP);
        List<StoredMessage> messages = wiring.sessions().readAll(sessionId);
        assertThat(messages.get(messages.size() - 1).text()).endsWith("生成复盘报告。");
        assertThat(wiring.sessions().endedSessions).contains(sessionId);
        assertThat(wiring.supervisor().state(sessionId).status()).isEqualTo(SessionState.ENDED);
        // 收场话术命中「到此结束」词面且无选题标记，豁免提前收尾闸门后零重试
        assertThat(runtime.calls()).hasSize(3);
    }

    @Test
    void 三振模型违规仍出新题_模板桥接零调用收场() {
        FakeAgentRuntime runtime = new FakeAgentRuntime(c -> INVALID_TURN);
        Wiring wiring = newSupervisor(runtime);
        String sessionId = createSession(wiring);

        wiring.supervisor().chat(sessionId, "胡话一");
        wiring.supervisor().chat(sessionId, "胡话二");
        Supervisor.SupervisorReply reply = wiring.supervisor().chat(sessionId, "胡话三");

        // 模型三振轮仍出新题：系统剥离模板收场语替换，不悬空新题、不追加模型调用
        assertThat(reply.reply())
                .contains("因连续无效作答提前结束")
                .doesNotContain("下一题");
        assertThat(wiring.sessions().endedSessions).contains(sessionId);
        assertThat(runtime.calls()).hasSize(3);
    }

    @Test
    void 拒绝轮连击不增不清() {
        FakeAgentRuntime runtime = new FakeAgentRuntime(c ->
                c.systemPrompt().contains("明确的拒绝")
                        ? "好的。下一题：谈谈 JVM。\n选题" : INVALID_TURN);
        Wiring wiring = newSupervisor(runtime);
        String sessionId = createSession(wiring);

        wiring.supervisor().chat(sessionId, "胡话一");
        wiring.supervisor().chat(sessionId, "不要");
        // 拒绝与无效两类机制互不污染：连击保持一
        assertThat(wiring.supervisor().state(sessionId).invalidStreak()).isEqualTo(1);

        wiring.supervisor().chat(sessionId, "胡话二");
        wiring.supervisor().chat(sessionId, "胡话三");

        assertThat(wiring.sessions().endedSessions).contains(sessionId);
    }

    @Test
    void 最终指令轮切题_照常出题清零不出局() {
        FakeAgentRuntime runtime = new FakeAgentRuntime(c ->
                c.systemPrompt().contains("连续两轮无效作答")
                        ? "这个回答切题，理解基本到位。下一题：谈谈 MySQL 索引。\n选题"
                        : INVALID_TURN);
        Wiring wiring = newSupervisor(runtime);
        String sessionId = createSession(wiring);

        wiring.supervisor().chat(sessionId, "胡话一");
        wiring.supervisor().chat(sessionId, "胡话二");
        Supervisor.SupervisorReply reply =
                wiring.supervisor().chat(sessionId, "JVM 分为堆栈方法区，堆又分新生代老年代");

        // 最终指令在场但回答切题：照常点评出新题、连击清零、会话继续
        assertThat(runtime.calls().get(2).systemPrompt()).contains("连续两轮无效作答");
        assertThat(reply.action()).isEqualTo(Supervisor.ACTION_NEXT);
        assertThat(wiring.supervisor().state(sessionId).invalidStreak()).isZero();
        assertThat(wiring.sessions().endedSessions).isEmpty();
        assertThat(wiring.supervisor().state(sessionId).status()).isNotEqualTo(SessionState.ENDED);
    }
}
