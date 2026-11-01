package com.sakura.interview.api;

import com.sakura.interview.agent.FakeAgentRuntime;
import com.sakura.interview.agent.Supervisor;
import com.sakura.interview.infra.LlmCaller;
import com.sakura.interview.infra.StructuredOutputInvoker;
import com.sakura.interview.session.ContextCompressor;
import com.sakura.interview.session.FakeReportStore;
import com.sakura.interview.session.FakeSessionStore;
import com.sakura.interview.session.ReportService;
import com.sakura.interview.session.StoredMessage;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class ChatStreamServiceTest {

    private static final String JD_JSON =
            "{\"direction\":\"Java后端\",\"skills\":[\"Spring\",\"MySQL\"],\"bonus\":[]}";

    private final FakeSessionStore sessions = new FakeSessionStore();
    private final FakeReportStore reports = new FakeReportStore();
    private final FakeAgentRuntime runtime = new FakeAgentRuntime(c -> "请讲讲HashMap的底层实现。");
    private final Supervisor supervisor = new Supervisor(runtime,
            new StructuredOutputInvoker(branchingCaller()),
            sessions, new ContextCompressor((sys, user) -> "合并摘要"),
            new ReportService(sessions, reports));
    private final ChatStreamService service =
            new ChatStreamService(supervisor, sessions, Runnable::run);

    /** 按系统提示词分流：简历分析出摘要、意图分类按关键词、其余按 JD 返回。 */
    private static LlmCaller branchingCaller() {
        return (sys, user) -> {
            if (sys.contains("简历分析器")) {
                return "{\"digest\":\"三年 Java 后端，主导订单系统\"}";
            }
            if (sys.contains("意图分类器")) {
                return user.contains("生成报告") || user.contains("结束面试")
                        ? "{\"intent\":\"REPORT\"}" : "{\"intent\":\"INTERVIEW\"}";
            }
            return JD_JSON;
        };
    }

    private static final class Recording implements ChatStreamService.SseSender {
        final List<String> events = new ArrayList<>();
        final List<Object> payloads = new ArrayList<>();

        @Override
        public void send(String event, Object data) {
            events.add(event);
            payloads.add(data);
        }
    }

    @Test
    void 面试轮事件顺序为消息增量动作指标与完成() {
        runtime.toolCallsPerCall(1);
        String sessionId = supervisor.createSession(null, "我的简历", "招 Java 后端", null).sessionId();
        Recording sender = new Recording();

        service.runTurn(sender, sessionId, "我先自我介绍");

        assertThat(sender.events).containsExactly(
                "message", "message", "action", "metrics", "done");
        // 前两段增量拼接等于回复全文（SSE 与落库一致口径）
        String first = (String) ((Map<?, ?>) sender.payloads.get(0)).get("delta");
        String second = (String) ((Map<?, ?>) sender.payloads.get(1)).get("delta");
        assertThat(first + second).isEqualTo("请讲讲HashMap的底层实现。");
        assertThat(((Map<?, ?>) sender.payloads.get(2)).get("type")).isEqualTo("QUESTION");
        Map<?, ?> metrics = (Map<?, ?>) sender.payloads.get(3);
        assertThat((long) metrics.get("outputTokens")).isEqualTo(10);
        assertThat((long) metrics.get("latencyMs")).isGreaterThanOrEqualTo(0);
        assertThat(((Map<?, ?>) sender.payloads.get(4)).get("round")).isEqualTo(1);
        // 题型随消息落库，会话详情可还原徽章
        StoredMessage interviewer = sessions.readAll(sessionId).get(1);
        assertThat(interviewer.action()).isEqualTo("QUESTION");
    }

    @Test
    void 追问轮无工具调用标注FOLLOW_UP不推进题号() {
        runtime.toolCallsPerCall(0);
        String sessionId = supervisor.createSession(null, "我的简历", "招 Java 后端", null).sessionId();

        service.runTurn(new Recording(), sessionId, "回答一");
        Recording second = new Recording();
        service.runTurn(second, sessionId, "回答二");

        assertThat(second.events).containsExactly("message", "message", "action", "metrics", "done");
        assertThat(((Map<?, ?>) second.payloads.get(2)).get("type")).isEqualTo("FOLLOW_UP");
    }

    @Test
    void 复盘意图无流式增量时整段补发一次消息() {
        String sessionId = supervisor.createSession(null, "我的简历", "招 Java 后端", null).sessionId();
        sessions.appendMessage(sessionId, "interviewer", "第一题", 0, 0);
        sessions.appendMessage(sessionId, "user", "回答一", 0, 0);
        sessions.appendMessage(sessionId, "interviewer", "第二题", 0, 0);
        sessions.appendMessage(sessionId, "user", "回答二", 0, 0);
        Recording sender = new Recording();

        service.runTurn(sender, sessionId, "生成报告");

        // 复盘不调模型：无增量，靠补发的整段消息保证前端气泡不为空
        assertThat(sender.events).containsExactly("message", "metrics", "done");
        assertThat(sender.events).doesNotContain("action");
        String reply = (String) ((Map<?, ?>) sender.payloads.get(0)).get("delta");
        assertThat(reply).contains("生成中");
    }

    @Test
    void 会话不存在发错误事件且不发完成() {
        Recording sender = new Recording();

        service.runTurn(sender, "s-none", "你好");

        assertThat(sender.events).containsExactly("error");
        assertThat(((Map<?, ?>) sender.payloads.get(0)).get("message"))
                .asString().contains("会话不存在");
    }
}
