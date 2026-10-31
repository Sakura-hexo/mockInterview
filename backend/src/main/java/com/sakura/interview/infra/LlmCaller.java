package com.sakura.interview.infra;

/**
 * LLM 文本调用端口：入参 system 与 user 提示词，返回回复文本。
 * 生产实现走 ChatModels，单测注入 fake，不调真实模型。
 */
@FunctionalInterface
public interface LlmCaller {

    String complete(String systemPrompt, String userPrompt);
}
