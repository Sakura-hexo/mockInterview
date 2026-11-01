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
import com.sakura.interview.tool.QuestionBankTool;
import reactor.core.publisher.Flux;

import java.time.Duration;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Executor;

/**
 * Supervisor：按意图与会话状态调度。只依赖 AgentRuntime、SessionStore 端口与
 * 结构化调用器，不 import 任何 AgentScope 类——端口化验收红线（换引擎只动 infra）。
 * 会话状态全部经 SessionStore 持久化（MySQL 事实源加 Redis 热存），
 * 每轮输入由 ContextAssembler 组装（前情摘要加最近窗口），重启可恢复。
 * 压缩经注入的执行器在关键路径外运行，流式轮次不等摘要；缺省同步执行兼容 CLI 与单测。
 */
public final class Supervisor {

    // 长会话后期面试官输出变重（实测单轮可到 120 秒级），留足余量防误杀
    private static final Duration TIMEOUT = Duration.ofSeconds(300);

    public static final String ACTION_QUESTION = "QUESTION";
    public static final String ACTION_FOLLOW_UP = "FOLLOW_UP";
    public static final String ACTION_NEXT = "NEXT";

    /** 出题标记行前缀：提示词约定新题以单独一行"选题"结尾（题型判定信号）。 */
    static final String PICK_MARK = "选题";

    /** 预检索查询里候选人回答的截取长度。 */
    private static final int ANSWER_EXCERPT_CHARS = 120;

    private final AgentRuntime runtime;
    private final StructuredOutputInvoker invoker;
    private final Router router;
    private final SessionStore store;
    private final ContextCompressor compressor;
    private final ReportService reportService;
    private final QuestionBankTool questionBank;
    private final Executor maintenanceExecutor;

    public Supervisor(AgentRuntime runtime, StructuredOutputInvoker invoker,
                      SessionStore store, ContextCompressor compressor,
                      ReportService reportService) {
        this(runtime, invoker, store, compressor, reportService, null, Runnable::run);
    }

    /** Web 装配用：预检索题库工具与后台压缩执行器。 */
    public Supervisor(AgentRuntime runtime, StructuredOutputInvoker invoker,
                      SessionStore store, ContextCompressor compressor,
                      ReportService reportService, QuestionBankTool questionBank,
                      Executor maintenanceExecutor) {
        this.runtime = runtime;
        this.invoker = invoker;
        this.store = store;
        this.compressor = compressor;
        this.reportService = reportService;
        this.questionBank = questionBank;
        this.maintenanceExecutor = maintenanceExecutor;
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
            case INTERVIEW -> {
                // 问满计划题数后收尾：不再预检索候选题，提示词切换为总结引导
                int asked = store.countQuestions(sessionId);
                String candidates = asked < state.config().questionCount()
                        ? candidatesFor(state, userText) : "";
                yield agentTurn(state, Intent.INTERVIEW,
                        AgentRuntime.ROLE_INTERVIEWER,
                        Prompts.interviewerSystem(state, asked, candidates),
                        userText, listener);
            }
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
        // 题型推导（选题标记启发式）：末尾标记行判新题，按 round 分首题与换题，
        // 无标记判追问（闲聊固定追问）；标记行从落库文本与已问题单中剥除
        String action = AgentRuntime.ROLE_CHAT.equals(role)
                ? ACTION_FOLLOW_UP
                : deriveAction(state, finalChunk.fullText());
        String displayText = stripPickMark(finalChunk.fullText());
        store.appendMessage(sessionId, role, displayText,
                finalChunk.inputTokens(), finalChunk.outputTokens(), action);
        if (AgentRuntime.ROLE_INTERVIEWER.equals(role)) {
            state.recordAsked(displayText);
            store.saveSession(state);
        }
        // 压缩移出关键路径：SSE 的 metrics 与 done 不等摘要；失败降级语义由压缩器自理
        maintenanceExecutor.execute(() -> {
            try {
                compressor.compressIfNeeded(store, sessionId);
            } catch (Throwable t) {
                System.err.printf("[上下文压缩] 后台执行异常：%s%n", t.getMessage());
            }
        });
        return new SupervisorReply(intent, finalChunk.fullText(),
                finalChunk.inputTokens(), finalChunk.outputTokens(), action);
    }

    private static String deriveAction(SessionState state, String fullText) {
        return hasPickMark(fullText)
                ? (state.round() == 0 ? ACTION_QUESTION : ACTION_NEXT)
                : ACTION_FOLLOW_UP;
    }

    /** 面试官轮预检索：按岗位方向与候选人当前回答组查询注入候选题，失败降级为空。 */
    private String candidatesFor(SessionState state, String userText) {
        if (questionBank == null) {
            return "";
        }
        String answer = userText.length() <= ANSWER_EXCERPT_CHARS
                ? userText : userText.substring(0, ANSWER_EXCERPT_CHARS);
        try {
            return questionBank.retrieveAsText(state.jdProfile().direction() + " " + answer);
        } catch (RuntimeException e) {
            System.err.printf("[预检索] 失败降级为无候选：%s%n", e.getMessage());
            return "";
        }
    }

    /** 出题标记：回复最后一个非空行以"选题"开头。 */
    static boolean hasPickMark(String fullText) {
        return lastNonEmptyLine(fullText).startsWith(PICK_MARK);
    }

    /** 剥除末尾的出题标记行：落库文本、已问题单与前端回显均不带标记。 */
    static String stripPickMark(String fullText) {
        if (fullText == null) {
            return "";
        }
        if (!hasPickMark(fullText)) {
            return fullText.stripTrailing();
        }
        String stripped = fullText.stripTrailing();
        int lineStart = stripped.lastIndexOf('\n') + 1;
        return stripped.substring(0, lineStart).stripTrailing();
    }

    private static String lastNonEmptyLine(String text) {
        if (text == null || text.isBlank()) {
            return "";
        }
        String stripped = text.stripTrailing();
        return stripped.substring(stripped.lastIndexOf('\n') + 1).strip();
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
