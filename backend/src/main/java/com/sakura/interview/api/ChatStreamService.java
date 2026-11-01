package com.sakura.interview.api;

import com.sakura.interview.agent.Intent;
import com.sakura.interview.agent.Supervisor;
import com.sakura.interview.session.SessionState;
import com.sakura.interview.session.SessionStore;
import io.github.resilience4j.ratelimiter.RateLimiter;
import io.github.resilience4j.ratelimiter.RateLimiterRegistry;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 面试对话 SSE 流（接口文档 4.3）：POST 打开后按 message、action、metrics、done
 * 推送，异常发 error 事件。事件构造集中在本类，SseSender 只做传输，
 * 单测注入记录型 sender 即可断言事件顺序与载荷，无需起 Web 容器。
 * 客户端断线不取消生成：发送失败静默吞掉，落库照常，前端靠 3 秒轮询恢复。
 * 会话级单飞守卫：同会话上一条仍在生成时拒绝新请求，防 MAX 加 1 的 seq 撞主键。
 * 每用户限流（可注入，阈值随注册表配置）与业务指标（轮时延、在飞连接 Gauge）。
 */
public final class ChatStreamService {

    /** 大于单轮模型超时 300 秒，留出落库与收尾余量。 */
    private static final long EMITTER_TIMEOUT_MILLIS = 320_000L;

    private final Supervisor supervisor;
    private final SessionStore store;
    private final Executor executor;
    private final MeterRegistry meterRegistry;
    private final RateLimiterRegistry rateLimiters;
    private final Set<String> inFlight = ConcurrentHashMap.newKeySet();
    private final AtomicLong activeTurns = new AtomicLong();

    public ChatStreamService(Supervisor supervisor, SessionStore store, Executor executor) {
        this(supervisor, store, executor, null, null);
    }

    /** Web 装配用：业务指标与每用户限流注册表，均可为空（单测口径不受限）。 */
    public ChatStreamService(Supervisor supervisor, SessionStore store, Executor executor,
                             MeterRegistry meterRegistry, RateLimiterRegistry rateLimiters) {
        this.supervisor = supervisor;
        this.store = store;
        this.executor = executor;
        this.meterRegistry = meterRegistry;
        this.rateLimiters = rateLimiters;
        if (meterRegistry != null) {
            meterRegistry.gauge("sse_active", activeTurns);
        }
    }

    /** SSE 事件传输出口：包一层便于单测替换为记录器。 */
    @FunctionalInterface
    public interface SseSender {

        void send(String event, Object data) throws IOException;
    }

    public SseEmitter chat(String sessionId, String content) {
        return chat(sessionId, null, content);
    }

    public SseEmitter chat(String sessionId, Long userId, String content) {
        if (rateLimiters != null && userId != null) {
            // 只按名查找：阈值配置归注册表所有（生产 10 轮每分钟，测试可注入更紧的口径）
            RateLimiter limiter = rateLimiters.rateLimiter("user-" + userId);
            if (!limiter.acquirePermission()) {
                throw ApiException.tooManyRequests("发送过于频繁，请稍后再试");
            }
        }
        if (!inFlight.add(sessionId)) {
            throw ApiException.conflict("上一条回复仍在生成中，请稍候再发送");
        }
        SseEmitter emitter = new SseEmitter(EMITTER_TIMEOUT_MILLIS);
        SseSender sender = (event, data) ->
                emitter.send(SseEmitter.event().name(event).data(data));
        executor.execute(() -> {
            activeTurns.incrementAndGet();
            try {
                runTurn(sender, sessionId, content);
            } finally {
                activeTurns.decrementAndGet();
                inFlight.remove(sessionId);
                completeQuietly(emitter);
            }
        });
        return emitter;
    }

    /** 单轮推送（包级可见供单测直调）：状态复检、增量转发与收尾事件的全部顺序逻辑。 */
    void runTurn(SseSender sender, String sessionId, String content) {
        long start = System.nanoTime();
        boolean[] streamed = {false};
        String[] actionTag = {"NONE"};
        try {
            // 状态复检：控制器校验后到线程池执行前的窗口里，会话可能已被结束
            String status = store.loadSession(sessionId).map(SessionState::status).orElse(null);
            if (status == null) {
                sendQuietly(sender, "error", Map.of("message", "会话不存在：" + sessionId));
                return;
            }
            if (!SessionState.IN_PROGRESS.equals(status)) {
                sendQuietly(sender, "error", Map.of("message", "面试未开始或已结束，无法继续对话"));
                return;
            }
            Supervisor.SupervisorReply reply = supervisor.chat(sessionId, content, delta -> {
                streamed[0] = true;
                sendQuietly(sender, "message", Map.of("delta", delta));
            });
            if (reply.intent() == Intent.INTERVIEW && reply.action() != null) {
                actionTag[0] = reply.action();
            }
            // 复盘等无模型流式输出的意图：整段补发一次 message，前端气泡不为空
            if (!streamed[0] && !reply.reply().isEmpty()) {
                sendQuietly(sender, "message", Map.of("delta", reply.reply()));
            }
            if (reply.intent() == Intent.INTERVIEW && reply.action() != null) {
                sendQuietly(sender, "action", Map.of("type", reply.action()));
            }
            sendQuietly(sender, "metrics", Map.of(
                    "inputTokens", reply.inputTokens(),
                    "outputTokens", reply.outputTokens(),
                    "latencyMs", (System.nanoTime() - start) / 1_000_000));
            int round = store.loadSession(sessionId).map(SessionState::round).orElse(0);
            sendQuietly(sender, "done", Map.of("round", round));
        } catch (RuntimeException e) {
            actionTag[0] = "ERROR";
            sendQuietly(sender, "error", Map.of("message", friendly(e)));
        } finally {
            recordTurn(start, actionTag[0]);
        }
    }

    /** 轮时延按题型打标记录，供面板观察各题型 p50 与 p95。 */
    private void recordTurn(long startNanos, String action) {
        if (meterRegistry == null) {
            return;
        }
        Timer.builder("interview_turn_seconds")
                .tag("action", action)
                .register(meterRegistry)
                .record(System.nanoTime() - startNanos, TimeUnit.NANOSECONDS);
    }

    private static String friendly(RuntimeException e) {
        if (e instanceof ApiException || e instanceof IllegalStateException) {
            return e.getMessage() == null ? "生成失败" : e.getMessage();
        }
        return "生成失败，请稍后重试";
    }

    private static void sendQuietly(SseSender sender, String event, Object data) {
        try {
            sender.send(event, data);
        } catch (IOException | IllegalStateException e) {
            // 客户端已断线或流已收尾：静默丢弃后续推送，不中断生成与落库
        }
    }

    private static void completeQuietly(SseEmitter emitter) {
        try {
            emitter.complete();
        } catch (IllegalStateException ignored) {
            // 已完成或已超时
        }
    }
}
