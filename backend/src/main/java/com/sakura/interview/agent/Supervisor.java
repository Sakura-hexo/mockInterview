package com.sakura.interview.agent;

import com.google.gson.JsonObject;
import com.sakura.interview.infra.StructuredOutputInvoker;
import com.sakura.interview.session.ContextAssembler;
import com.sakura.interview.session.ContextCompressor;
import com.sakura.interview.session.ReportService;
import com.sakura.interview.session.SessionConfig;
import com.sakura.interview.session.SessionState;
import com.sakura.interview.session.SessionStore;
import com.sakura.interview.session.SessionSummary;
import com.sakura.interview.tool.JdParseTool;
import com.sakura.interview.tool.JdProfile;
import reactor.core.publisher.Flux;

import java.time.Duration;
import java.util.List;
import java.util.UUID;

/**
 * Supervisor：按意图与会话状态调度。只依赖 AgentRuntime、SessionStore 端口与
 * 结构化调用器，不 import 任何 AgentScope 类——端口化验收红线（换引擎只动 infra）。
 * 会话状态全部经 SessionStore 持久化（MySQL 事实源加 Redis 热存），
 * 每轮输入由 ContextAssembler 组装（前情摘要加最近窗口），重启可恢复。
 * 步骤 7 增流式监听与题型推导：增量回调供 SSE 转发，action 按工具信号判定。
 */
public final class Supervisor {

    // 长会话后期面试官输出变重（实测单轮可到 120 秒级），留足余量防误杀
    private static final Duration TIMEOUT = Duration.ofSeconds(300);

    public static final String ACTION_QUESTION = "QUESTION";
    public static final String ACTION_FOLLOW_UP = "FOLLOW_UP";
    public static final String ACTION_NEXT = "NEXT";

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

    public record SupervisorReply(Intent intent, String reply,
                                  long inputTokens, long outputTokens, String action) {
    }

    /** 建会话（CLI 用）：无归属用户，配置取默认。 */
    public CreateResult createSession(String resumeText, String jdText) {
        return createSession(null, resumeText, jdText, null);
    }

    /** 建会话（Web 用）：简历经结构化调用器产出摘要，JD 经解析工具提要点，配置与归属落库。 */
    public CreateResult createSession(Long userId, String resumeText, String jdText,
                                      SessionConfig config) {
        JsonObject resumeObj = invoker.invoke(Prompts.RESUME_ANALYST_SYSTEM, resumeText, JsonObject.class);
        String digest = resumeObj.has("digest")
                ? resumeObj.get("digest").getAsString() : "简历解析未产出摘要";
        JdProfile jd = new JdParseTool(invoker).parse(jdText);
        String sessionId = "s-" + UUID.randomUUID().toString().substring(0, 8);
        store.saveSession(new SessionState(sessionId, userId, digest, jd, config,
                SessionState.CREATED, null, List.of(), 0));
        return new CreateResult(sessionId, digest, jd);
    }

    /** 单轮对话（无监听）：CLI 与单测口径。 */
    public SupervisorReply chat(String sessionId, String userText) {
        return chat(sessionId, userText, null);
    }

    /** 单轮对话（带监听）：文本增量逐段回调，供 SSE 转发；编排逻辑与无监听版完全一致。 */
    public SupervisorReply chat(String sessionId, String userText, TurnListener listener) {
        SessionState state = requireState(sessionId);
        Intent intent = router.route(userText);
        return switch (intent) {
            // 复盘元命令不落消息：报告评估读全量日志，不能被元命令污染问答配对
            case REPORT -> new SupervisorReply(intent, reportService.handle(sessionId), 0, 0, null);
            case CHAT -> agentTurn(state, Intent.CHAT,
                    AgentRuntime.ROLE_CHAT, Prompts.CHAT_SYSTEM, userText, listener);
            case INTERVIEW -> agentTurn(state, Intent.INTERVIEW,
                    AgentRuntime.ROLE_INTERVIEWER, Prompts.interviewerSystem(state), userText, listener);
        };
    }

    /** 会话状态（CLI 恢复模式回显用）。 */
    public SessionState state(String sessionId) {
        return requireState(sessionId);
    }

    private SupervisorReply agentTurn(SessionState state, Intent intent, String role,
                                      String systemPrompt, String userText, TurnListener listener) {
        String sessionId = state.sessionId();
        store.appendMessage(sessionId, "user", userText, 0, 0);
        String contextInput = ContextAssembler.assemble(
                store.readWindow(sessionId, ContextAssembler.WINDOW_MESSAGES),
                store.loadSummary(sessionId).map(SessionSummary::text).orElse(null));
        StreamChunk.Final finalChunk = streamTurn(role, sessionId, systemPrompt, contextInput, listener);
        // 题型推导（工具信号启发式）：闲聊回复固定按追问标注避免推进题号；
        // 面试官回复无工具调用视为追问，有工具调用时首题记 QUESTION 其余记 NEXT
        String action = AgentRuntime.ROLE_CHAT.equals(role)
                ? ACTION_FOLLOW_UP
                : deriveAction(state, finalChunk);
        store.appendMessage(sessionId, role, finalChunk.fullText(),
                finalChunk.inputTokens(), finalChunk.outputTokens(), action);
        if (AgentRuntime.ROLE_INTERVIEWER.equals(role)) {
            state.recordAsked(finalChunk.fullText());
            store.saveSession(state);
        }
        compressor.compressIfNeeded(store, sessionId);
        return new SupervisorReply(intent, finalChunk.fullText(),
                finalChunk.inputTokens(), finalChunk.outputTokens(), action);
    }

    private static String deriveAction(SessionState state, StreamChunk.Final finalChunk) {
        if (finalChunk.toolCalls() == 0) {
            return ACTION_FOLLOW_UP;
        }
        return state.round() == 0 ? ACTION_QUESTION : ACTION_NEXT;
    }

    private StreamChunk.Final streamTurn(String role, String sessionId, String systemPrompt,
                                         String contextInput, TurnListener listener) {
        Flux<StreamChunk> flux = runtime.streamGenerate(role, sessionId, systemPrompt, contextInput);
        if (listener != null) {
            flux = flux.doOnNext(chunk -> {
                if (chunk instanceof StreamChunk.Delta delta && !delta.text().isEmpty()) {
                    listener.onDelta(delta.text());
                }
            });
        }
        StreamChunk.Final finalChunk = flux.ofType(StreamChunk.Final.class)
                .reduce((first, second) -> second)
                .block(TIMEOUT);
        return finalChunk == null ? new StreamChunk.Final("", 0, 0, 0) : finalChunk;
    }

    private SessionState requireState(String sessionId) {
        return store.loadSession(sessionId)
                .orElseThrow(() -> new IllegalStateException("会话不存在：" + sessionId));
    }
}
