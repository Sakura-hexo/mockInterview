package com.sakura.interview.agent;

import com.sakura.interview.infra.LlmCaller;
import com.sakura.interview.infra.StructuredOutputInvoker;
import com.sakura.interview.rag.SearchHit;
import com.sakura.interview.session.ContextCompressor;
import com.sakura.interview.session.FakeReportStore;
import com.sakura.interview.session.FakeSessionStore;
import com.sakura.interview.session.ReportService;
import com.sakura.interview.session.ReportTask;
import com.sakura.interview.session.StoredMessage;
import com.sakura.interview.tool.QuestionBankTool;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class SupervisorTest {

    private static final String JD_JSON =
            "{\"direction\":\"Java后端\",\"skills\":[\"Spring\",\"MySQL\"],\"bonus\":[]}";

    /** 按系统提示词分流的假调用器：简历分析出摘要、意图分类出意图、其余按 JD 返回。 */
    private static LlmCaller branchingCaller() {
        return (sys, user) -> {
            if (sys.contains("简历分析器")) {
                return "{\"digest\":\"三年 Java 后端，主导订单系统，熟悉并发与 JVM 调优\"}";
            }
            if (sys.contains("意图分类器")) {
                return user.contains("天气") ? "{\"intent\":\"CHAT\"}" : "{\"intent\":\"INTERVIEW\"}";
            }
            return JD_JSON;
        };
    }

    private record Wiring(Supervisor supervisor, FakeSessionStore sessions, FakeReportStore reports) {
    }

    private static Wiring newSupervisor(FakeAgentRuntime runtime) {
        FakeSessionStore sessions = new FakeSessionStore();
        FakeReportStore reports = new FakeReportStore();
        Supervisor supervisor = new Supervisor(runtime, new StructuredOutputInvoker(branchingCaller()),
                sessions, new ContextCompressor((sys, user) -> "合并摘要"),
                new ReportService(sessions, reports));
        return new Wiring(supervisor, sessions, reports);
    }

    @Test
    void 建会话产出简历摘要与岗位要点() {
        Wiring wiring = newSupervisor(new FakeAgentRuntime(c -> "x"));
        Supervisor.CreateResult created = wiring.supervisor().createSession("我的简历", "招 Java 后端");
        assertThat(created.resumeDigest()).contains("三年 Java 后端");
        assertThat(created.jdProfile().skills()).contains("Spring");
        assertThat(wiring.supervisor().state(created.sessionId())).isNotNull();
    }

    @Test
    void 面试意图走面试官并注入简历与岗位要点() {
        FakeAgentRuntime runtime = new FakeAgentRuntime(c -> "请介绍一下 HashMap 的底层结构。");
        Wiring wiring = newSupervisor(runtime);
        String sessionId = wiring.supervisor().createSession("我的简历", "招 Java 后端").sessionId();

        Supervisor.SupervisorReply reply = wiring.supervisor().chat(sessionId, "我先自我介绍，三年经验");

        assertThat(reply.intent()).isEqualTo(Intent.INTERVIEW);
        assertThat(reply.reply()).contains("HashMap");
        FakeAgentRuntime.Call call = runtime.calls().get(0);
        assertThat(call.role()).isEqualTo(AgentRuntime.ROLE_INTERVIEWER);
        assertThat(call.systemPrompt())
                .contains("三年 Java 后端")
                .contains("Spring")
                .contains("选题")
                .contains("禁止重复")
                .doesNotContain("search_question_bank");
        assertThat(call.userInput()).contains("我先自我介绍，三年经验");
    }

    @Test
    void 多轮后已问题目注入防重复清单且会话延续() {
        FakeAgentRuntime runtime = new FakeAgentRuntime(c -> "追问：HashMap 树化条件是什么？");
        Wiring wiring = newSupervisor(runtime);
        String sessionId = wiring.supervisor().createSession("我的简历", "招 Java 后端").sessionId();

        wiring.supervisor().chat(sessionId, "回答一");
        wiring.supervisor().chat(sessionId, "回答二");

        assertThat(runtime.calls()).hasSize(2);
        String secondPrompt = runtime.calls().get(1).systemPrompt();
        assertThat(secondPrompt).contains("追问：HashMap 树化条件是什么？");
        assertThat(runtime.calls().get(1).sessionId()).isEqualTo(runtime.calls().get(0).sessionId());
    }

    @Test
    void 闲聊意图走chat角色() {
        FakeAgentRuntime runtime = new FakeAgentRuntime(c -> "面试加油！");
        Wiring wiring = newSupervisor(runtime);
        String sessionId = wiring.supervisor().createSession("我的简历", "招 Java 后端").sessionId();

        Supervisor.SupervisorReply reply = wiring.supervisor().chat(sessionId, "今天天气不错");

        assertThat(reply.intent()).isEqualTo(Intent.CHAT);
        assertThat(reply.reply()).isEqualTo("面试加油！");
        assertThat(runtime.calls()).hasSize(1);
        assertThat(runtime.calls().get(0).role()).isEqualTo(AgentRuntime.ROLE_CHAT);
    }

    @Test
    void 复盘意图题数不足不建任务不调模型() {
        FakeAgentRuntime runtime = new FakeAgentRuntime(c -> "不应被调用");
        Wiring wiring = newSupervisor(runtime);
        String sessionId = wiring.supervisor().createSession("我的简历", "招 Java 后端").sessionId();

        Supervisor.SupervisorReply reply = wiring.supervisor().chat(sessionId, "我想结束面试");

        assertThat(reply.intent()).isEqualTo(Intent.REPORT);
        assertThat(reply.reply()).contains("样本太少").contains("继续作答");
        assertThat(wiring.reports().tasks).isEmpty();
        assertThat(runtime.calls()).isEmpty();
    }

    @Test
    void 复盘意图题数足够提交任务并结束会话() {
        FakeAgentRuntime runtime = new FakeAgentRuntime(c -> "不应被调用");
        Wiring wiring = newSupervisor(runtime);
        String sessionId = wiring.supervisor().createSession("我的简历", "招 Java 后端").sessionId();
        wiring.sessions().appendMessage(sessionId, "interviewer", "第一题", 0, 0);
        wiring.sessions().appendMessage(sessionId, "user", "回答一", 0, 0);
        wiring.sessions().appendMessage(sessionId, "interviewer", "第二题", 0, 0);
        wiring.sessions().appendMessage(sessionId, "user", "回答二", 0, 0);

        Supervisor.SupervisorReply reply = wiring.supervisor().chat(sessionId, "生成报告");

        assertThat(reply.intent()).isEqualTo(Intent.REPORT);
        assertThat(reply.reply()).contains("生成中").contains("看报告");
        ReportTask task = wiring.reports().tasks.get(sessionId);
        assertThat(task).isNotNull();
        assertThat(task.status()).isEqualTo(ReportTask.GENERATING);
        assertThat(wiring.sessions().endedSessions).containsExactly(sessionId);
        assertThat(runtime.calls()).isEmpty();
    }

    @Test
    void 会话不存在抛异常() {
        Wiring wiring = newSupervisor(new FakeAgentRuntime(c -> "x"));
        assertThatThrownBy(() -> wiring.supervisor().chat("s-none", "你好"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("会话不存在");
    }

    @Test
    void 题型按选题标记推导_首题追问与换题() {
        AtomicInteger mode = new AtomicInteger(1);
        FakeAgentRuntime runtime = new FakeAgentRuntime(c -> mode.get() == 1
                ? "很好。下一题：谈谈 JVM 调优。\n选题" : "追问：能结合项目再具体一点吗？");
        Wiring wiring = newSupervisor(runtime);
        String sessionId = wiring.supervisor().createSession("我的简历", "招 Java 后端").sessionId();

        // 首轮（round 0）带出题标记记 QUESTION，之后带标记记 NEXT，标记行从落库剥除
        assertThat(wiring.supervisor().chat(sessionId, "回答一").action())
                .isEqualTo(Supervisor.ACTION_QUESTION);
        assertThat(wiring.supervisor().chat(sessionId, "回答二").action())
                .isEqualTo(Supervisor.ACTION_NEXT);
        List<StoredMessage> messages = wiring.sessions().readAll(sessionId);
        assertThat(messages.get(1).action()).isEqualTo("QUESTION");
        assertThat(messages.get(1).text()).isEqualTo("很好。下一题：谈谈 JVM 调优。");
        assertThat(messages.get(3).action()).isEqualTo("NEXT");

        // 无标记视为追问
        mode.set(0);
        Supervisor.SupervisorReply followUp = wiring.supervisor().chat(sessionId, "回答三");
        assertThat(followUp.action()).isEqualTo(Supervisor.ACTION_FOLLOW_UP);
        assertThat(followUp.reply()).doesNotContain("选题");
    }

    @Test
    void 未问满时提示词保持出题规则() {
        FakeAgentRuntime runtime = new FakeAgentRuntime(c -> "好的。下一题：谈谈 JVM。\n选题");
        Wiring wiring = newSupervisor(runtime);
        String sessionId = wiring.supervisor().createSession("我的简历", "招 Java 后端").sessionId();
        wiring.sessions().appendMessage(sessionId, "interviewer", "开场白", 0, 0, "QUESTION");

        wiring.supervisor().chat(sessionId, "回答一");

        String prompt = runtime.calls().get(0).systemPrompt();
        assertThat(prompt)
                .contains("计划 10 道题，已问 1 道")
                .contains("出新题的回复必须")
                .contains("最后一道题")
                .doesNotContain("已问满");
    }

    @Test
    void 问满计划题数后提示词切换为收尾规则() {
        FakeAgentRuntime runtime = new FakeAgentRuntime(c -> "本场总结：整体表现良好。");
        Wiring wiring = newSupervisor(runtime);
        String sessionId = wiring.supervisor().createSession("我的简历", "招 Java 后端").sessionId();
        for (int i = 1; i <= 10; i++) {
            wiring.sessions().appendMessage(sessionId, "interviewer", "第" + i + "题",
                    0, 0, i == 1 ? "QUESTION" : "NEXT");
        }

        wiring.supervisor().chat(sessionId, "回答最后一题");

        String prompt = runtime.calls().get(0).systemPrompt();
        assertThat(prompt)
                .contains("计划 10 道题，已问 10 道")
                .contains("已问满")
                .contains("复盘报告")
                .doesNotContain("出新题的回复必须")
                .doesNotContain("候选题都不合适");
    }

    @Test
    void 预检索候选题注入面试官系统提示词() {
        FakeAgentRuntime runtime = new FakeAgentRuntime(c -> "好的。下一题：谈谈索引失效场景。\n选题");
        FakeSessionStore sessions = new FakeSessionStore();
        QuestionBankTool bank = new QuestionBankTool((query, topK) -> List.of(
                new SearchHit("QB-001", "索引失效的场景有哪些", "MySQL", "seed", 0.9f)));
        Supervisor supervisor = new Supervisor(runtime, new StructuredOutputInvoker(branchingCaller()),
                sessions, new ContextCompressor((sys, user) -> "合并摘要"),
                new ReportService(sessions, new FakeReportStore()), bank, Runnable::run);
        String sessionId = supervisor.createSession("我的简历", "招 Java 后端").sessionId();

        supervisor.chat(sessionId, "我讲了项目里的慢查询优化");

        assertThat(runtime.calls().get(0).systemPrompt())
                .contains("候选题").contains("QB-001").contains("索引失效");
    }

    @Test
    void 流式监听逐段收到文本增量() {
        FakeAgentRuntime runtime = new FakeAgentRuntime(c -> "请讲讲HashMap的底层。");
        Wiring wiring = newSupervisor(runtime);
        String sessionId = wiring.supervisor().createSession("我的简历", "招 Java 后端").sessionId();

        List<String> deltas = new ArrayList<>();
        Supervisor.SupervisorReply reply =
                wiring.supervisor().chat(sessionId, "回答一", deltas::add);

        // 假运行时按两段下发：增量拼接恒等于回复全文（SSE 与落库一致口径）
        assertThat(deltas).containsExactly("请讲", "讲HashMap的底层。");
        assertThat(String.join("", deltas)).isEqualTo(reply.reply());
    }

    @Test
    void 压缩移出关键路径由后台执行器承担() {
        FakeAgentRuntime runtime = new FakeAgentRuntime(c -> "面试官问题");
        FakeSessionStore sessions = new FakeSessionStore();
        AtomicInteger compressCalls = new AtomicInteger();
        List<Runnable> background = new ArrayList<>();
        Supervisor supervisor = new Supervisor(runtime, new StructuredOutputInvoker(branchingCaller()),
                sessions, new ContextCompressor((sys, user) -> {
                    compressCalls.incrementAndGet();
                    return "合并摘要";
                }), new ReportService(sessions, new FakeReportStore()), null, background::add);
        String sessionId = supervisor.createSession("我的简历", "招 Java 后端").sessionId();

        for (int i = 1; i <= 15; i++) {
            supervisor.chat(sessionId, "回答" + i);
        }

        // 关键路径零压缩调用：30 条消息已满足触发条件，但摘要模型未被调用
        assertThat(compressCalls.get()).isZero();
        // 后台任务已排队，放行后压缩执行并落摘要
        background.forEach(Runnable::run);
        assertThat(compressCalls.get()).isGreaterThanOrEqualTo(1);
        assertThat(sessions.summaries.get(sessionId)).isNotNull();
    }
}
