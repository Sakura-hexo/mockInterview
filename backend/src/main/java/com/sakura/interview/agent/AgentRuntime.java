package com.sakura.interview.agent;

import reactor.core.publisher.Mono;

/**
 * 智能体运行时端口：编排层只依赖此接口，AgentScope 实现在 infra，测试用 FakeAgentRuntime。
 * 端口无状态——每次调用独立，跨轮记忆由调用方经 ContextAssembler 组装进 userInput 注入，
 * 端口自身不累积历史。角色常量集中定义在此。
 */
public interface AgentRuntime {

    String ROLE_INTERVIEWER = "interviewer";
    String ROLE_CHAT = "chat";

    Mono<AgentReply> generate(String role, String sessionId, String systemPrompt, String userInput);

    String modelName(String role);
}
