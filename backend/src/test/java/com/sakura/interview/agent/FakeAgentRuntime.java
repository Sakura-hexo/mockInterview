package com.sakura.interview.agent;

import reactor.core.publisher.Flux;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;

/**
 * 编排单测用假运行时：记录每次调用的 role、sessionId、系统提示词与输入，
 * 按预设函数回复——编排逻辑的正确性不依赖真实模型。
 * 输入 token 以输入长度近似、输出 token 固定，保证可断言；
 * 回复按三个增量下发以覆盖流式路径，toolCalls 可注入用于题型推导断言。
 */
public final class FakeAgentRuntime implements AgentRuntime {

    public record Call(String role, String sessionId, String systemPrompt, String userInput) {
    }

    private final List<Call> calls = new ArrayList<>();
    private final Function<Call, String> responder;
    private int toolCalls = 0;

    public FakeAgentRuntime(Function<Call, String> responder) {
        this.responder = responder;
    }

    public List<Call> calls() {
        return calls;
    }

    /** 设定每次调用的工具调用次数，驱动题型推导（QUESTION、NEXT）。 */
    public void toolCallsPerCall(int toolCalls) {
        this.toolCalls = toolCalls;
    }

    @Override
    public Flux<StreamChunk> streamGenerate(String role, String sessionId,
                                            String systemPrompt, String userInput) {
        Call call = new Call(role, sessionId, systemPrompt, userInput);
        calls.add(call);
        String text = responder.apply(call);
        String first = text.length() <= 2 ? text : text.substring(0, 2);
        String rest = text.length() <= 2 ? "" : text.substring(2);
        return Flux.just(
                new StreamChunk.Delta(first),
                new StreamChunk.Delta(rest),
                new StreamChunk.Final(text, Math.max(1, userInput.length()), 10, toolCalls));
    }

    @Override
    public String modelName(String role) {
        return "fake-" + role;
    }
}
