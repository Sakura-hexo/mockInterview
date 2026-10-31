package com.liuzihe.interview.agent;

/**
 * 单次智能体调用的结果：回复文本与 token 用量（埋点与消息落库用），空缺时为 0。
 */
public record AgentReply(String text, long inputTokens, long outputTokens) {
}
