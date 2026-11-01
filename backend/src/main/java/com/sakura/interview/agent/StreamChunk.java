package com.sakura.interview.agent;

/**
 * 流式调用分段：Delta 为待推送的文本增量；Final 为收尾快照——
 * fullText 恒等于本流全部 Delta 的拼接（无任何 Delta 时回退终稿文本），
 * 保证推送给前端的内容与落库文本一致；toolCalls 为本轮工具调用次数，
 * 供题型推导（无调用视为追问，有调用视为换题）。
 */
public sealed interface StreamChunk {

    record Delta(String text) implements StreamChunk {
    }

    record Final(String fullText, long inputTokens, long outputTokens, int toolCalls)
            implements StreamChunk {
    }
}
