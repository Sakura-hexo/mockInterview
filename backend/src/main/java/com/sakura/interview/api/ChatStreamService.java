package com.sakura.interview.api;

import com.sakura.interview.agent.Intent;
import com.sakura.interview.agent.Supervisor;
import com.sakura.interview.session.SessionState;
import com.sakura.interview.session.SessionStore;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.util.Map;
import java.util.concurrent.Executor;

/**
 * 面试对话 SSE 流（接口文档 4.3）：POST 打开后按 message、action、metrics、done
 * 推送，异常发 error 事件。事件构造集中在本类，SseSender 只做传输，
 * 单测注入记录型 sender 即可断言事件顺序与载荷，无需起 Web 容器。
 * 客户端断线不取消生成：发送失败静默吞掉，落库照常，前端靠 3 秒轮询恢复。
 */
public final class ChatStreamService {

    /** 大于单轮模型超时 300 秒，留出落库与收尾余量。 */
    private static final long EMITTER_TIMEOUT_MILLIS = 320_000L;

    private final Supervisor supervisor;
    private final SessionStore store;
    private final Executor executor;

    public ChatStreamService(Supervisor supervisor, SessionStore store, Executor executor) {
        this.supervisor = supervisor;
        this.store = store;
        this.executor = executor;
    }

    /** SSE 事件传输出口：包一层便于单测替换为记录器。 */
    @FunctionalInterface
    public interface SseSender {

        void send(String event, Object data) throws IOException;
    }

    public SseEmitter chat(String sessionId, String content) {
        SseEmitter emitter = new SseEmitter(EMITTER_TIMEOUT_MILLIS);
        SseSender sender = (event, data) ->
                emitter.send(SseEmitter.event().name(event).data(data));
        executor.execute(() -> {
            try {
                runTurn(sender, sessionId, content);
            } finally {
                completeQuietly(emitter);
            }
        });
        return emitter;
    }

    /** 单轮推送（包级可见供单测直调）：增量转发与收尾事件的全部顺序逻辑。 */
    void runTurn(SseSender sender, String sessionId, String content) {
        long start = System.nanoTime();
        boolean[] streamed = {false};
        try {
            Supervisor.SupervisorReply reply = supervisor.chat(sessionId, content, delta -> {
                streamed[0] = true;
                sendQuietly(sender, "message", Map.of("delta", delta));
            });
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
            sendQuietly(sender, "error", Map.of("message", friendly(e)));
        }
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
