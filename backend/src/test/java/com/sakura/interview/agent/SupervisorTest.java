package com.sakura.interview.agent;

import com.sakura.interview.infra.LlmCaller;
import com.sakura.interview.infra.StructuredOutputInvoker;
import com.sakura.interview.session.ContextCompressor;
import com.sakura.interview.session.FakeReportStore;
import com.sakura.interview.session.FakeSessionStore;
import com.sakura.interview.session.ReportService;
import com.sakura.interview.session.ReportTask;
import org.junit.jupiter.api.Test;

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
                .contains("search_question_bank")
                .contains("禁止重复");
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
}
