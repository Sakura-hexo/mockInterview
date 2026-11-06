package com.sakura.interview.api;

import java.io.IOException;
import java.util.List;

/**
 * TTS 关闭态占位：未配置 TTS_PROVIDER 时 voices 为空，接口一律 404，
 * 前端探测 404 即回落浏览器 speechSynthesis（方案 plans/2026-11-06/服务端TTS合成-优化方案.md）。
 */
public final class DisabledTtsSpeaker implements TtsSpeaker {

    @Override
    public List<String> voices() {
        return List.of();
    }

    @Override
    public byte[] synthesize(String text, String voice) throws IOException {
        throw new IOException("TTS 未启用");
    }
}
