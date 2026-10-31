package com.sakura.interview.infra;

import com.sakura.interview.agent.AgentRuntime;
import io.agentscope.core.ReActAgent;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.message.Msg;
import io.agentscope.core.state.InMemoryAgentStateStore;
import io.agentscope.core.tool.AgentTool;
import io.agentscope.core.tool.Toolkit;
import reactor.core.publisher.Mono;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * AgentRuntime 的 AgentScope 实现：换引擎只改本类。
 * agent 每轮重建以刷新系统提示词（已问题目清单会变），跨轮记忆由按 角色:会话
 * 缓存的 stateStore 延续。面试官 maxIters 3 挂题库工具，chat maxIters 1 无工具；
 * 不传内置文件工具即权限收敛（字节码核验零内置工具引用）。
 */
public final class AgentScopeRuntime implements AgentRuntime {

    private static final Duration TIMEOUT = Duration.ofSeconds(120);
    private static final int INTERVIEWER_MAX_ITERS = 3;
    private static final int CHAT_MAX_ITERS = 1;

    private final Toolkit interviewerToolkit;
    private final Map<String, InMemoryAgentStateStore> stores = new ConcurrentHashMap<>();

    public AgentScopeRuntime(List<AgentTool> interviewerTools) {
        Toolkit toolkit = new Toolkit();
        for (AgentTool tool : interviewerTools) {
            toolkit.registerAgentTool(tool);
        }
        this.interviewerToolkit = toolkit;
    }

    @Override
    public Mono<String> generate(String role, String sessionId, String systemPrompt, String userInput) {
        return Mono.fromCallable(() -> callBlocking(role, sessionId, systemPrompt, userInput));
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

    private String callBlocking(String role, String sessionId, String systemPrompt, String userInput) {
        ReActAgent agent = buildAgent(role, sessionId, systemPrompt);
        RuntimeContext context = RuntimeContext.builder()
                .userId("supervisor")
                .sessionId(sessionId)
                .build();
        Msg reply = agent.call(userInput, context).block(TIMEOUT);
        return reply == null || reply.getTextContent() == null ? "" : reply.getTextContent();
    }

    private ReActAgent buildAgent(String role, String sessionId, String systemPrompt) {
        InMemoryAgentStateStore store = stores.computeIfAbsent(
                role + ":" + sessionId, key -> new InMemoryAgentStateStore());
        ReActAgent.Builder builder = ReActAgent.builder()
                .name(role)
                .sysPrompt(systemPrompt)
                .model(ChatModels.chat())
                .stateStore(store)
                .enableMetaTool(false);
        if (AgentRuntime.ROLE_INTERVIEWER.equals(role)) {
            builder.maxIters(INTERVIEWER_MAX_ITERS).toolkit(interviewerToolkit);
        } else {
            builder.maxIters(CHAT_MAX_ITERS);
        }
        return builder.build();
    }
}
