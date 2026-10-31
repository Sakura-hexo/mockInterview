package com.sakura.interview.agent;

import reactor.core.publisher.Mono;

/**
 * 智能体运行时端口：编排层只依赖此接口，AgentScope 实现在 infra，测试用 FakeAgentRuntime。
 * interrupt 能力随步骤 7 SSE 引入。角色常量集中定义在此。
 */
public interface AgentRuntime {

    String ROLE_INTERVIEWER = "interviewer";
    String ROLE_CHAT = "chat";

    /**
     * 对话型调用：系统提示词由调用方组装，端口不管上下文组装，
     * 仅保证同 role 与 sessionId 的跨轮记忆延续。
     */
    Mono<String> generate(String role, String sessionId, String systemPrompt, String userInput);

    String modelName(String role);
}
