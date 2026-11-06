package com.sakura.interview.agent;

import com.sakura.interview.infra.StructuredOutputInvoker;
import com.sakura.interview.session.AnalysisCache;
import com.sakura.interview.session.ContextAssembler;
import com.sakura.interview.session.ContextCompressor;
import com.sakura.interview.session.ReportService;
import com.sakura.interview.session.SessionConfig;
import com.sakura.interview.session.SessionState;
import com.sakura.interview.session.SessionStore;
import com.sakura.interview.session.SessionSummary;
import com.sakura.interview.tool.JdProfile;
import com.sakura.interview.tool.QuestionBankTool;
import reactor.core.publisher.Flux;

import java.time.Duration;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
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

    /** 无效作答标记：出题轮与选题合并为"选题 无效"行，三振收场轮单独一行"无效"。 */
    static final String INVALID_MARK = "无效";

    /** 三振阈值：连续无效作答达此值，系统出面收场并结束会话（执法权在系统不在模型）。 */
    private static final int INVALID_STRIKES = 3;

    /** 三振模板收场语：模型违反指令仍出新题时的零调用桥接（仿闸门桥接）。 */
    private static final String STRUCK_OUT_CLOSING =
            "本场面试因连续无效作答提前结束。你可以在结束后生成复盘报告，查看逐题评分与改进建议。";

    /** 预检索查询里候选人回答的截取长度。 */
    private static final int ANSWER_EXCERPT_CHARS = 120;

    /** 首轮等后台分析落库的轮询上限：60 次 100 毫秒，超时降级继续出题。 */
    private static final int ANALYSIS_WAIT_POLLS = 60;
    private static final long ANALYSIS_POLL_INTERVAL_MS = 100;

    private final AgentRuntime runtime;
    private final StructuredOutputInvoker invoker;
    private final SessionAnalyst analyst;
    private final Router router;
    private final SessionStore store;
    private final ContextCompressor compressor;
    private final ReportService reportService;
    private final QuestionBankTool questionBank;
    private final Executor maintenanceExecutor;
    /** 分析在飞集合：提交时认领（含排队未执行），防后台与首轮兜底重复跑同一会话的分析。 */
    private final Set<String> analyzing = ConcurrentHashMap.newKeySet();

    public Supervisor(AgentRuntime runtime, StructuredOutputInvoker invoker,
                      SessionStore store, ContextCompressor compressor,
                      ReportService reportService) {
        this(runtime, invoker, new SessionAnalyst(invoker, AnalysisCache.NONE), store,
                compressor, reportService, null, Runnable::run);
    }

    /** Web 装配用：注入带缓存的分析器、预检索题库工具与后台压缩执行器。 */
    public Supervisor(AgentRuntime runtime, StructuredOutputInvoker invoker,
                      SessionAnalyst analyst, SessionStore store, ContextCompressor compressor,
                      ReportService reportService, QuestionBankTool questionBank,
                      Executor maintenanceExecutor) {
        this.runtime = runtime;
        this.invoker = invoker;
        this.analyst = analyst;
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

    /** 建会话（Web 用）：先落占位会话秒回，分析提交后台线程跑完回填——冷创建不再等模型。 */
    public CreateResult createSession(Long userId, String resumeText, String jdText,
                                      SessionConfig config) {
        String sessionId = "s-" + UUID.randomUUID().toString().substring(0, 8);
        SessionState state = new SessionState(sessionId, userId, "",
                new JdProfile("Java 后端", List.of(), List.of()), config,
                SessionState.CREATED, null, List.of(), 0);
        state.markAnalysisPending(resumeText, jdText);
        store.saveSession(state);
        // 认领发生在提交时而非线程体内：排队期间首轮兜底能看见在飞，不会重复跑分析
        if (analyzing.add(sessionId)) {
            maintenanceExecutor.execute(() -> {
                try {
                    analyzeAndStore(sessionId, resumeText, jdText);
                } finally {
                    analyzing.remove(sessionId);
                }
            });
        }
        // 同步执行器（CLI 与单测）下此刻分析已完成，回读拿真实摘要；异步下仍是占位
        return store.loadSession(sessionId)
                .map(fresh -> new CreateResult(sessionId, fresh.resumeDigest(), fresh.jdProfile()))
                .orElseGet(() -> new CreateResult(sessionId, "", state.jdProfile()));
    }

    /** 分析并回填（假定已在飞集合里）：失败打日志留待下轮兜底，不清 pending。 */
    private void analyzeAndStore(String sessionId, String resumeText, String jdText) {
        try {
            SessionAnalyst.Analysis analysis = analyst.analyze(resumeText, jdText);
            store.completeAnalysis(sessionId, analysis.resumeDigest(), analysis.jdProfile());
        } catch (RuntimeException e) {
            System.err.printf("[会话分析] 失败留待下轮兜底重试：%s%n", e.getMessage());
        }
    }

    /**
     * 首轮兜底：分析未完成时，在飞就短暂等它落库（用户打字窗口通常早已完成），
     * 不在飞（进程重启或后台失败）就本线程补跑；仍不完整则带占位画像降级出题，
     * 占位方向足以支撑预检索与出题，下一轮会再自动重试。
     */
    private SessionState ensureAnalyzed(SessionState state) {
        if (!state.analysisPending()) {
            return state;
        }
        String sessionId = state.sessionId();
        if (analyzing.add(sessionId)) {
            try {
                analyzeAndStore(sessionId, state.resumeText(), state.jdText());
            } finally {
                analyzing.remove(sessionId);
            }
        } else {
            waitForBackgroundAnalysis(sessionId);
        }
        return requireState(sessionId);
    }

    /** 等后台分析落库：在飞标记消失（完成或失败）或超 6 秒为止。 */
    private void waitForBackgroundAnalysis(String sessionId) {
        for (int i = 0; i < ANALYSIS_WAIT_POLLS && analyzing.contains(sessionId); i++) {
            sleepQuietly(ANALYSIS_POLL_INTERVAL_MS);
        }
    }

    private static void sleepQuietly(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
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
                // 分析兜底：占位建会话的后台分析若未完成，此处等待或补跑
                state = ensureAnalyzed(state);
                // 问满计划题数后收尾：不再预检索候选题，提示词切换为总结引导
                int asked = store.countQuestions(sessionId);
                boolean remaining = asked < state.config().questionCount();
                String candidates = remaining ? candidatesFor(state, userText) : "";
                // 拒绝信号升为本轮级指令：三轮纯提示词迭代均被模型劝答本能压过
                String system = Prompts.interviewerSystem(state, asked, candidates);
                if (remaining && RefusalDetector.isRefusal(userText)) {
                    system = system + Prompts.REFUSAL_DIRECTIVE;
                }
                // 无效作答连击按档位升轮级指令：一振警告后果、二振最终收场指令
                if (remaining && state.invalidStreak() == 1) {
                    system = system + Prompts.INVALID_WARN_DIRECTIVE;
                } else if (remaining && state.invalidStreak() >= 2) {
                    system = system + Prompts.INVALID_FINAL_DIRECTIVE;
                }
                yield interviewerTurn(state, system, userText, listener, remaining, candidates);
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
        StreamChunk.Final finalChunk = streamTurn(role, sessionId, systemPrompt,
                contextInput(sessionId), listener);
        return finalizeTurn(state, intent, role, finalChunk);
    }

    /** 面试官轮：与通用轮的差别在未问满时套提前收尾闸门与无效三振执法，落库口径完全一致。 */
    private SupervisorReply interviewerTurn(SessionState state, String systemPrompt, String userText,
                                            TurnListener listener, boolean remaining,
                                            String candidates) {
        String sessionId = state.sessionId();
        store.appendMessage(sessionId, "user", userText, 0, 0);
        String context = contextInput(sessionId);
        StreamChunk.Final finalChunk = streamTurn(
                AgentRuntime.ROLE_INTERVIEWER, sessionId, systemPrompt, context, listener);
        // 提前收尾闸门对三振收场轮豁免：连击两次后收场是预期行为，不能当漂移拦下重试
        StreamChunk.Final effective = remaining && state.invalidStreak() < 2
                ? enforceNotConcluded(state, systemPrompt, context, finalChunk, listener, candidates)
                : finalChunk;
        // 无效作答记账：拒绝轮两类机制各管各的，不增也不清；其余轮按标记判——带无效标记连击加一，切题清零
        boolean struckOut = false;
        if (!remaining || !RefusalDetector.isRefusal(userText)) {
            state.recordAnswerValidity(!hasInvalidMark(effective.fullText()));
            struckOut = state.invalidStreak() >= INVALID_STRIKES;
            if (struckOut) {
                effective = closeOnStrikes(effective);
            }
        }
        SupervisorReply reply = finalizeTurn(state, Intent.INTERVIEW, AgentRuntime.ROLE_INTERVIEWER, effective);
        // 收场消息先落库再置 ENDED：同轮 saveSession 与 endSession 的写序不颠倒，状态不会被轮内回写覆盖
        if (struckOut) {
            store.endSession(sessionId);
        }
        return reply;
    }

    /** 三振收场：模型按最终指令收场则原样使用，违反指令仍出新题则模板桥接（零额外调用）。 */
    private static StreamChunk.Final closeOnStrikes(StreamChunk.Final reply) {
        if (!hasPickMark(reply.fullText())) {
            return reply;
        }
        System.err.println("[无效三振] 模型仍出新题，已模板桥接收场");
        return new StreamChunk.Final(STRUCK_OUT_CLOSING,
                reply.inputTokens(), reply.outputTokens(), reply.toolCalls());
    }

    /**
     * 未问满防提前收尾闸门：回复无选题标记且命中告别话术视为漂移——
     * 带 FAREWELL_DIRECTIVE 重试一次，仍失败则用候选题首条做确定性桥接（零额外模型调用）。
     * 有标记的追问/出题轮与问满后的合法告别不进本闸门。
     */
    private StreamChunk.Final enforceNotConcluded(SessionState state, String systemPrompt,
                                                  String contextInput, StreamChunk.Final first,
                                                  TurnListener listener, String candidates) {
        String text = first.fullText();
        if (hasPickMark(text) || !FarewellDetector.isConclusion(text)) {
            return first;
        }
        StreamChunk.Final retry = streamTurn(AgentRuntime.ROLE_INTERVIEWER, state.sessionId(),
                systemPrompt + Prompts.FAREWELL_DIRECTIVE, contextInput, listener);
        if (hasPickMark(retry.fullText()) || !FarewellDetector.isConclusion(retry.fullText())) {
            return retry;
        }
        String bridged = bridgedQuestion(candidates);
        if (bridged == null) {
            System.err.println("[提前收尾闸门] 重试仍告别且无候选题，放行原文");
            return first;
        }
        System.err.println("[提前收尾闸门] 重试仍告别，已用候选题桥接补题");
        return new StreamChunk.Final(bridged, first.inputTokens() + retry.inputTokens(),
                first.outputTokens() + retry.outputTokens(), first.toolCalls());
    }

    /** 候选题首条改写为面试官口吻的下一题（带选题标记），供闸门兜底。 */
    private static String bridgedQuestion(String candidates) {
        if (candidates == null || candidates.isBlank()) {
            return null;
        }
        String firstLine = candidates.lines().findFirst().orElse("");
        int idEnd = firstLine.indexOf(' ');
        String question = idEnd > 0 ? firstLine.substring(idEnd + 1) : firstLine;
        int paren = question.indexOf("（标签：");
        if (paren > 0) {
            question = question.substring(0, paren);
        }
        if (question.isBlank()) {
            return null;
        }
        return "本场还有题目未问完，我们继续。\n\n下一题：" + question.strip() + "\n选题";
    }

    private String contextInput(String sessionId) {
        return ContextAssembler.assemble(
                store.readWindow(sessionId, ContextAssembler.WINDOW_MESSAGES),
                store.loadSummary(sessionId).map(SessionSummary::text).orElse(null));
    }

    private SupervisorReply finalizeTurn(SessionState state, Intent intent, String role,
                                         StreamChunk.Final finalChunk) {
        String sessionId = state.sessionId();
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

    /**
     * 无效标记判定：任一行以「选题」开头且含「无效」（真机实测模型爱把标记行写在指出之后、
     * 新题之前，再按老习惯补一行裸选题），或收场轮任一行为「无效」。位置无关，只认行面。
     */
    static boolean hasInvalidMark(String fullText) {
        if (fullText == null) {
            return false;
        }
        return fullText.lines().map(String::strip).anyMatch(line ->
                INVALID_MARK.equals(line)
                        || (line.startsWith(PICK_MARK) && line.contains(INVALID_MARK)));
    }

    /** 剥除全部标记行（选题或无效，正文任意位置）：落库文本、已问题单与前端回显均不带标记。 */
    static String stripPickMark(String fullText) {
        if (fullText == null) {
            return "";
        }
        StringBuilder kept = new StringBuilder();
        for (String line : fullText.split("\n", -1)) {
            String stripped = line.strip();
            if (stripped.startsWith(PICK_MARK) || stripped.equals(INVALID_MARK)) {
                continue;
            }
            kept.append(line).append('\n');
        }
        return kept.toString().stripTrailing();
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
