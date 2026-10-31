package com.sakura.interview.agent;

import reactor.core.publisher.Mono;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;

/**
 * 编排单测用假运行时：记录每次调用的 role、sessionId、系统提示词与输入，
 * 按预设函数回复——编排逻辑的正确性不依赖真实模型。
 */
final class FakeAgentRuntime implements AgentRuntime {

    record Call(String role, String sessionId, String systemPrompt, String userInput) {
    }

    private final List<Call> calls = new ArrayList<>();
    private final Function<Call, String> responder;

    FakeAgentRuntime(Function<Call, String> responder) {
        this.responder = responder;
    }

    List<Call> calls() {
        return calls;
    }

    @Override
    public Mono<String> generate(String role, String sessionId, String systemPrompt, String userInput) {
        Call call = new Call(role, sessionId, systemPrompt, userInput);
        calls.add(call);
        return Mono.just(responder.apply(call));
    }

    @Override
    public String modelName(String role) {
        return "fake-" + role;
    }
}
