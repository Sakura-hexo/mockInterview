package com.liuzihe.interview.session;

/**
 * 落库消息：seq 为会话内单调递增序号，token 用量来自模型回复，
 * 用户消息的 token 记 0，面试官消息记录本轮实际用量。
 */
public record StoredMessage(int seq, String role, String text,
                            long inputTokens, long outputTokens) {
}
