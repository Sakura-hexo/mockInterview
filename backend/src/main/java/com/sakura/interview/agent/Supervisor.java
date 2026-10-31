package com.sakura.interview.agent;

import com.google.gson.JsonObject;
import com.sakura.interview.infra.StructuredOutputInvoker;
import com.sakura.interview.tool.JdParseTool;
import com.sakura.interview.tool.JdProfile;
import reactor.core.publisher.Mono;

import java.time.Duration;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Supervisor：按意图与会话状态调度。只依赖 AgentRuntime 端口与结构化调用器，
 * 不 import 任何 AgentScope 类——端口化验收红线（换引擎只动 infra）。
 * 会话状态当前为内存版，步骤 5 换 Redis 与 MySQL 持久化。
 */
public final class Supervisor {

    private static final Duration TIMEOUT = Duration.ofSeconds(120);

    private final AgentRuntime runtime;
    private final StructuredOutputInvoker invoker;
    private final Router router;
    private final Map<String, InMemorySessionState> sessions = new ConcurrentHashMap<>();

    public Supervisor(AgentRuntime runtime, StructuredOutputInvoker invoker) {
        this.runtime = runtime;
        this.invoker = invoker;
        this.router = new Router(invoker);
    }

    public record CreateResult(String sessionId, String resumeDigest, JdProfile jdProfile) {
    }

    public record SupervisorReply(Intent intent, String reply) {
    }

    /** 建会话：简历经结构化调用器产出摘要，JD 经解析工具提要点。 */
    public CreateResult createSession(String resumeText, String jdText) {
        JsonObject resumeObj = invoker.invoke(Prompts.RESUME_ANALYST_SYSTEM, resumeText, JsonObject.class);
        String digest = resumeObj.has("digest")
                ? resumeObj.get("digest").getAsString() : "简历解析未产出摘要";
        JdProfile jd = new JdParseTool(invoker).parse(jdText);
        String sessionId = "s-" + UUID.randomUUID().toString().substring(0, 8);
        sessions.put(sessionId, new InMemorySessionState(sessionId, digest, jd));
        return new CreateResult(sessionId, digest, jd);
    }

    /** 单轮对话：路由分流到面试官、闲聊或复盘占位。 */
    public SupervisorReply chat(String sessionId, String userText) {
        InMemorySessionState state = requireState(sessionId);
        Intent intent = router.route(userText);
        return switch (intent) {
            case REPORT -> new SupervisorReply(intent, Prompts.REPORT_PLACEHOLDER);
            case CHAT -> new SupervisorReply(intent, block(
                    runtime.generate(AgentRuntime.ROLE_CHAT, sessionId, Prompts.CHAT_SYSTEM, userText)));
            case INTERVIEW -> interviewTurn(state, userText);
        };
    }

    /** 查看会话状态（步骤 5 将由持久化层替代此内存态）。 */
    public InMemorySessionState state(String sessionId) {
        return requireState(sessionId);
    }

    private SupervisorReply interviewTurn(InMemorySessionState state, String userText) {
        String reply = block(runtime.generate(AgentRuntime.ROLE_INTERVIEWER, state.sessionId(),
                Prompts.interviewerSystem(state), userText));
        state.recordAsked(reply);
        return new SupervisorReply(Intent.INTERVIEW, reply);
    }

    private static String block(Mono<String> mono) {
        String reply = mono.block(TIMEOUT);
        return reply == null ? "" : reply;
    }

    private InMemorySessionState requireState(String sessionId) {
        InMemorySessionState state = sessions.get(sessionId);
        if (state == null) {
            throw new IllegalStateException("会话不存在：" + sessionId);
        }
        return state;
    }
}
