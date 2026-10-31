package com.sakura.interview.infra;

import io.agentscope.core.ReActAgent;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.message.Msg;
import io.agentscope.core.state.InMemoryAgentStateStore;

import java.time.Duration;
import java.util.UUID;

/**
 * LlmCaller 的生产实现：每次调用构建独立会话的一次性智能体，不携带历史，
 * 模型来源经 ChatModels 按 MODEL_PROVIDER 分派。
 */
public final class AgentLlmCaller implements LlmCaller {

    private static final Duration TIMEOUT = Duration.ofSeconds(120);

    @Override
    public String complete(String systemPrompt, String userPrompt) {
        ReActAgent agent = ReActAgent.builder()
                .name("llm-caller")
                .sysPrompt(systemPrompt)
                .model(ChatModels.chat())
                .stateStore(new InMemoryAgentStateStore())
                .build();
        RuntimeContext context = RuntimeContext.builder()
                .userId("llm-caller")
                .sessionId(UUID.randomUUID().toString())
                .build();
        Msg reply = agent.call(userPrompt, context).block(TIMEOUT);
        return reply == null || reply.getTextContent() == null ? "" : reply.getTextContent();
    }
}
