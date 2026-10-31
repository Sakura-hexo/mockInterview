package com.sakura.interview.agent;

import com.sakura.interview.infra.LlmCaller;
import com.sakura.interview.infra.StructuredOutputInvoker;
import com.sakura.interview.session.ContextCompressor;
import com.sakura.interview.session.FakeSessionStore;
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

    private static Supervisor newSupervisor(FakeAgentRuntime runtime) {
        return new Supervisor(runtime, new StructuredOutputInvoker(branchingCaller()),
                new FakeSessionStore(), new ContextCompressor((sys, user) -> "合并摘要"));
    }

    @Test
    void 建会话产出简历摘要与岗位要点() {
        Supervisor supervisor = newSupervisor(new FakeAgentRuntime(c -> "x"));
        Supervisor.CreateResult created = supervisor.createSession("我的简历", "招 Java 后端");
        assertThat(created.resumeDigest()).contains("三年 Java 后端");
        assertThat(created.jdProfile().skills()).contains("Spring");
        assertThat(supervisor.state(created.sessionId())).isNotNull();
    }

    @Test
    void 面试意图走面试官并注入简历与岗位要点() {
        FakeAgentRuntime runtime = new FakeAgentRuntime(c -> "请介绍一下 HashMap 的底层结构。");
        Supervisor supervisor = newSupervisor(runtime);
        String sessionId = supervisor.createSession("我的简历", "招 Java 后端").sessionId();

        Supervisor.SupervisorReply reply = supervisor.chat(sessionId, "我先自我介绍，三年经验");

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
        Supervisor supervisor = newSupervisor(runtime);
        String sessionId = supervisor.createSession("我的简历", "招 Java 后端").sessionId();

        supervisor.chat(sessionId, "回答一");
        supervisor.chat(sessionId, "回答二");

        assertThat(runtime.calls()).hasSize(2);
        String secondPrompt = runtime.calls().get(1).systemPrompt();
        assertThat(secondPrompt).contains("追问：HashMap 树化条件是什么？");
        assertThat(runtime.calls().get(1).sessionId()).isEqualTo(runtime.calls().get(0).sessionId());
    }

    @Test
    void 闲聊意图走chat角色() {
        FakeAgentRuntime runtime = new FakeAgentRuntime(c -> "面试加油！");
        Supervisor supervisor = newSupervisor(runtime);
        String sessionId = supervisor.createSession("我的简历", "招 Java 后端").sessionId();

        Supervisor.SupervisorReply reply = supervisor.chat(sessionId, "今天天气不错");

        assertThat(reply.intent()).isEqualTo(Intent.CHAT);
        assertThat(reply.reply()).isEqualTo("面试加油！");
        assertThat(runtime.calls()).hasSize(1);
        assertThat(runtime.calls().get(0).role()).isEqualTo(AgentRuntime.ROLE_CHAT);
    }

    @Test
    void 复盘意图返回占位不调智能体() {
        FakeAgentRuntime runtime = new FakeAgentRuntime(c -> "不应被调用");
        Supervisor supervisor = newSupervisor(runtime);
        String sessionId = supervisor.createSession("我的简历", "招 Java 后端").sessionId();

        Supervisor.SupervisorReply reply = supervisor.chat(sessionId, "我想结束面试");

        assertThat(reply.intent()).isEqualTo(Intent.REPORT);
        assertThat(reply.reply()).contains("复盘报告");
        assertThat(runtime.calls()).isEmpty();
    }

    @Test
    void 会话不存在抛异常() {
        Supervisor supervisor = newSupervisor(new FakeAgentRuntime(c -> "x"));
        assertThatThrownBy(() -> supervisor.chat("s-none", "你好"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("会话不存在");
    }
}
