package com.liuzihe.interview.agent;

import com.google.gson.JsonObject;
import com.liuzihe.interview.infra.StructuredOutputInvoker;
import com.liuzihe.interview.session.ContextAssembler;
import com.liuzihe.interview.session.ContextCompressor;
import com.liuzihe.interview.session.ReportService;
import com.liuzihe.interview.session.SessionState;
import com.liuzihe.interview.session.SessionStore;
import com.liuzihe.interview.session.SessionSummary;
import com.liuzihe.interview.tool.JdParseTool;
import com.liuzihe.interview.tool.JdProfile;
import reactor.core.publisher.Mono;

import java.time.Duration;
import java.util.UUID;

/**
 * Supervisor：按意图与会话状态调度。只依赖 AgentRuntime、SessionStore 端口与
 * 结构化调用器，不 import 任何 AgentScope 类——端口化验收红线（换引擎只动 infra）。
 * 步骤 5 起会话状态全部经 SessionStore 持久化（MySQL 事实源加 Redis 热存），
 * 每轮输入由 ContextAssembler 组装（前情摘要加最近窗口），重启可恢复。
 */
public final class Supervisor {

    // 长会话后期面试官输出变重（实测单轮可到 120 秒级），留足余量防误杀
    private static final Duration TIMEOUT = Duration.ofSeconds(300);

    private final AgentRuntime runtime;
    private final StructuredOutputInvoker invoker;
    private final Router router;
    private final SessionStore store;
    private final ContextCompressor compressor;
    private final ReportService reportService;

    public Supervisor(AgentRuntime runtime, StructuredOutputInvoker invoker,
                      SessionStore store, ContextCompressor compressor,
                      ReportService reportService) {
        this.runtime = runtime;
        this.invoker = invoker;
        this.store = store;
        this.compressor = compressor;
        this.reportService = reportService;
        this.router = new Router(invoker);
    }

    public record CreateResult(String sessionId, String resumeDigest, JdProfile jdProfile) {
    }

    public record SupervisorReply(Intent intent, String reply, long inputTokens, long outputTokens) {
    }

    /** 建会话：简历经结构化调用器产出摘要，JD 经解析工具提要点，元数据落库。 */
    public CreateResult createSession(String resumeText, String jdText) {
        JsonObject resumeObj = invoker.invoke(Prompts.RESUME_ANALYST_SYSTEM, resumeText, JsonObject.class);
        String digest = resumeObj.has("digest")
                ? resumeObj.get("digest").getAsString() : "简历解析未产出摘要";
        JdProfile jd = new JdParseTool(invoker).parse(jdText);
        String sessionId = "s-" + UUID.randomUUID().toString().substring(0, 8);
        store.saveSession(new SessionState(sessionId, digest, jd));
        return new CreateResult(sessionId, digest, jd);
    }

    /** 单轮对话：路由分流到面试官、闲聊或复盘流程，历史经组装器注入。 */
    public SupervisorReply chat(String sessionId, String userText) {
        SessionState state = requireState(sessionId);
        Intent intent = router.route(userText);
        return switch (intent) {
            // 复盘元命令不落消息：报告评估读全量日志，不能被元命令污染问答配对
            case REPORT -> new SupervisorReply(intent, reportService.handle(sessionId), 0, 0);
            case CHAT -> agentTurn(state, Intent.CHAT,
                    AgentRuntime.ROLE_CHAT, Prompts.CHAT_SYSTEM, userText);
            case INTERVIEW -> agentTurn(state, Intent.INTERVIEW,
                    AgentRuntime.ROLE_INTERVIEWER, Prompts.interviewerSystem(state), userText);
        };
    }

    /** 会话状态（CLI 恢复模式回显用）。 */
    public SessionState state(String sessionId) {
        return requireState(sessionId);
    }

    private SupervisorReply agentTurn(SessionState state, Intent intent, String role,
                                      String systemPrompt, String userText) {
        String sessionId = state.sessionId();
        store.appendMessage(sessionId, "user", userText, 0, 0);
        String contextInput = ContextAssembler.assemble(
                store.readWindow(sessionId, ContextAssembler.WINDOW_MESSAGES),
                store.loadSummary(sessionId).map(SessionSummary::text).orElse(null));
        AgentReply reply = block(runtime.generate(role, sessionId, systemPrompt, contextInput));
        store.appendMessage(sessionId, role, reply.text(), reply.inputTokens(), reply.outputTokens());
        if (AgentRuntime.ROLE_INTERVIEWER.equals(role)) {
            state.recordAsked(reply.text());
            store.saveSession(state);
        }
        compressor.compressIfNeeded(store, sessionId);
        return new SupervisorReply(intent, reply.text(), reply.inputTokens(), reply.outputTokens());
    }

    private static AgentReply block(Mono<AgentReply> mono) {
        AgentReply reply = mono.block(TIMEOUT);
        return reply == null ? new AgentReply("", 0, 0) : reply;
    }

    private SessionState requireState(String sessionId) {
        return store.loadSession(sessionId)
                .orElseThrow(() -> new IllegalStateException("会话不存在：" + sessionId));
    }
}
