package com.sakura.interview.session;

/**
 * 落库消息：seq 为会话内单调递增序号，token 用量来自模型回复，
 * 用户消息的 token 记 0，面试官消息记录本轮实际用量。
 * action 为面试官消息的题型标注（QUESTION、FOLLOW_UP、NEXT，闲聊消息不设），
 * createdAt 为秒级 ISO 时间戳，供会话详情接口回带。
 */
public record StoredMessage(int seq, String role, String text,
                            long inputTokens, long outputTokens,
                            String action, String createdAt) {

    public StoredMessage(int seq, String role, String text,
                         long inputTokens, long outputTokens) {
        this(seq, role, text, inputTokens, outputTokens, null, null);
    }

    public StoredMessage(int seq, String role, String text,
                         long inputTokens, long outputTokens, String action) {
        this(seq, role, text, inputTokens, outputTokens, action, null);
    }
}
