package com.sakura.interview.infra;

import com.sakura.interview.agent.AgentRuntime;
import com.sakura.interview.agent.StreamChunk;
import io.agentscope.core.ReActAgent;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.event.AgentEvent;
import io.agentscope.core.event.AgentEventType;
import io.agentscope.core.event.AgentResultEvent;
import io.agentscope.core.event.TextBlockDeltaEvent;
import io.agentscope.core.message.Msg;
import io.agentscope.core.model.ChatUsage;
import io.agentscope.core.state.InMemoryAgentStateStore;
import reactor.core.publisher.Flux;

import java.time.Duration;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * AgentRuntime 的 AgentScope 实现：换引擎只改本类。
 * 无状态——每次调用构建全新 agent（stateStore 仅作 AgentScope 必填占位、
 * 不跨轮复用），历史由 Supervisor 组装进 userInput 注入，杜绝自动累积导致的
 * token 线性膨胀。角色统一单轮生成：题库候选由 Supervisor 预检索注入系统提示词，
 * 不挂任何工具（字节码核验零内置工具引用）。
 * 流式口径：全部 TEXT_BLOCK_DELTA 依序转发，Final.fullText 取其拼接——
 * 推送内容与落库文本恒一致；思考块与工具事件只计数不转发。
 */
public final class AgentScopeRuntime implements AgentRuntime {

    @Override
    public Flux<StreamChunk> streamGenerate(String role, String sessionId,
                                            String systemPrompt, String userInput) {
        ReActAgent agent = buildAgent(role, systemPrompt);
        RuntimeContext context = RuntimeContext.builder()
                .userId("supervisor")
                .sessionId(sessionId)
                .build();
        StringBuilder accumulated = new StringBuilder();
        AtomicInteger toolCalls = new AtomicInteger();
        return agent.streamEvents(userInput, context)
                .<StreamChunk>handle((event, sink) -> {
                    AgentEventType type = event.getType();
                    if (type == AgentEventType.TEXT_BLOCK_DELTA) {
                        String delta = ((TextBlockDeltaEvent) event).getDelta();
                        if (delta != null && !delta.isEmpty()) {
                            accumulated.append(delta);
                            sink.next(new StreamChunk.Delta(delta));
                        }
                    } else if (type == AgentEventType.TOOL_CALL_START) {
                        toolCalls.incrementAndGet();
                    } else if (type == AgentEventType.AGENT_RESULT) {
                        sink.next(toFinal(((AgentResultEvent) event).getResult(),
                                accumulated, toolCalls.get()));
                    }
                });
    }

    @Override
    public String modelName(String role) {
        // 角色分档模型随步骤 8 落地，当前全 provider 同模型
        return switch (EnvKeys.modelProvider()) {
            case "openai" -> EnvKeys.openAiModel();
            case "ollama" -> EnvKeys.ollamaModel();
            default -> EnvKeys.dashScopeModel();
        };
    }

    /** 终稿快照：无任何文本增量时回退终稿消息文本，用量取终稿消息 usage。 */
    private static StreamChunk.Final toFinal(Msg result, StringBuilder accumulated, int toolCalls) {
        String fullText = accumulated.isEmpty() ? safeText(result) : accumulated.toString();
        ChatUsage usage = result == null ? null : result.getUsage();
        return new StreamChunk.Final(fullText,
                usage == null ? 0 : usage.getInputTokens(),
                usage == null ? 0 : usage.getOutputTokens(),
                toolCalls);
    }

    private static String safeText(Msg result) {
        if (result == null || result.getTextContent() == null) {
            return "";
        }
        return result.getTextContent();
    }

    private ReActAgent buildAgent(String role, String systemPrompt) {
        // 角色分级：面试官走主力档，闲聊走快档；两档互挂 fallbackModel 降级
        String tier = AgentRuntime.ROLE_INTERVIEWER.equals(role)
                ? ChatModels.TIER_MAIN : ChatModels.TIER_FAST;
        return ReActAgent.builder()
                .name(role)
                .sysPrompt(systemPrompt)
                .model(ChatModels.chat(tier))
                .fallbackModel(ChatModels.chat(ChatModels.otherTier(tier)))
                .stateStore(new InMemoryAgentStateStore())
                .maxIters(1) // 单轮生成：无工具可调，出题靠预检索注入
                .enableMetaTool(false)
                .build();
    }
}
