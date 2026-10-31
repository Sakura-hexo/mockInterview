package com.liuzihe.interview.infra;

import com.liuzihe.interview.agent.AgentReply;
import com.liuzihe.interview.agent.AgentRuntime;
import io.agentscope.core.ReActAgent;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.message.Msg;
import io.agentscope.core.model.ChatUsage;
import io.agentscope.core.state.InMemoryAgentStateStore;
import io.agentscope.core.tool.AgentTool;
import io.agentscope.core.tool.Toolkit;
import reactor.core.publisher.Mono;

import java.time.Duration;
import java.util.List;

/**
 * AgentRuntime 的 AgentScope 实现：换引擎只改本类。
 * 步骤 5 起无状态——每次调用构建全新 agent（stateStore 仅作 AgentScope 必填占位、
 * 不跨轮复用），历史由 Supervisor 组装进 userInput 注入，杜绝自动累积导致的
 * token 线性膨胀。面试官 maxIters 3 挂题库工具，chat maxIters 1；
 * 不传内置文件工具即权限收敛（字节码核验零内置工具引用）。
 */
public final class AgentScopeRuntime implements AgentRuntime {

    private static final Duration TIMEOUT = Duration.ofSeconds(300);
    private static final int INTERVIEWER_MAX_ITERS = 3;
    private static final int CHAT_MAX_ITERS = 1;

    private final Toolkit interviewerToolkit;

    public AgentScopeRuntime(List<AgentTool> interviewerTools) {
        Toolkit toolkit = new Toolkit();
        for (AgentTool tool : interviewerTools) {
            toolkit.registerAgentTool(tool);
        }
        this.interviewerToolkit = toolkit;
    }

    @Override
    public Mono<AgentReply> generate(String role, String sessionId, String systemPrompt, String userInput) {
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

    private AgentReply callBlocking(String role, String sessionId, String systemPrompt, String userInput) {
        ReActAgent agent = buildAgent(role, systemPrompt);
        RuntimeContext context = RuntimeContext.builder()
                .userId("supervisor")
                .sessionId(sessionId)
                .build();
        Msg reply = agent.call(userInput, context).block(TIMEOUT);
        if (reply == null) {
            return new AgentReply("", 0, 0);
        }
        String text = reply.getTextContent() == null ? "" : reply.getTextContent();
        ChatUsage usage = reply.getUsage();
        return new AgentReply(text,
                usage == null ? 0 : usage.getInputTokens(),
                usage == null ? 0 : usage.getOutputTokens());
    }

    private ReActAgent buildAgent(String role, String systemPrompt) {
        ReActAgent.Builder builder = ReActAgent.builder()
                .name(role)
                .sysPrompt(systemPrompt)
                .model(ChatModels.chat())
                .stateStore(new InMemoryAgentStateStore())
                .enableMetaTool(false);
        if (AgentRuntime.ROLE_INTERVIEWER.equals(role)) {
            builder.maxIters(INTERVIEWER_MAX_ITERS).toolkit(interviewerToolkit);
        } else {
            builder.maxIters(CHAT_MAX_ITERS);
        }
        return builder.build();
    }
}
