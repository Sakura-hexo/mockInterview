package com.sakura.interview.agent;

/**
 * 单轮生成的增量监听：每收到一个文本 Delta 回调一次。
 * Web 层用它把增量转发为 SSE message 事件；CLI 不传监听（两参 chat）零开销。
 */
@FunctionalInterface
public interface TurnListener {

    void onDelta(String delta);
}
