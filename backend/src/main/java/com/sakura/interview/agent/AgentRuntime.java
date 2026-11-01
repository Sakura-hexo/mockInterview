package com.sakura.interview.agent;

import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/**
 * 智能体运行时端口：编排层只依赖此接口，AgentScope 实现在 infra，测试用 FakeAgentRuntime。
 * 端口无状态——每次调用独立，跨轮记忆由调用方经 ContextAssembler 组装进 userInput 注入，
 * 端口自身不累积历史。角色常量集中定义在此。
 */
public interface AgentRuntime {

    String ROLE_INTERVIEWER = "interviewer";
    String ROLE_CHAT = "chat";

    /** 流式生成：产出若干 Delta 与恰好一个 Final。 */
    Flux<StreamChunk> streamGenerate(String role, String sessionId, String systemPrompt, String userInput);

    /** 整段生成：默认实现取流式结果的最后一个 Final，CLI 与单测沿用旧口径。 */
    default Mono<AgentReply> generate(String role, String sessionId, String systemPrompt, String userInput) {
        return streamGenerate(role, sessionId, systemPrompt, userInput)
                .ofType(StreamChunk.Final.class)
                .reduce((first, second) -> second)
                .map(finalChunk -> new AgentReply(finalChunk.fullText(),
                        finalChunk.inputTokens(), finalChunk.outputTokens()))
                .defaultIfEmpty(new AgentReply("", 0, 0));
    }

    String modelName(String role);
}
