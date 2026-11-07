package com.sakura.interview.api;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.io.IOException;

/**
 * 服务端语音识别接口：GET /api/asr/health 供前端探测（未启用 404，前端回落
 * 浏览器识别）；POST /api/asr 上传录音原始字节（webm 等浏览器格式），返回识别
 * 文本。识别无缓存（同文本复述率低），时长与大小上限由边车统一执法。
 */
@RestController
@RequestMapping("/api/asr")
public final class AsrController {

    private static final int MAX_BYTES = 10 * 1024 * 1024;

    private final AsrTranscriber transcriber;

    public AsrController(AsrTranscriber transcriber) {
        this.transcriber = transcriber;
    }

    public record HealthResponse(boolean enabled) {
    }

    @GetMapping("/health")
    public ApiEnvelope<HealthResponse> health() {
        if (!transcriber.enabled()) {
            throw ApiException.notFound("接口不存在");
        }
        return ApiEnvelope.ok(new HealthResponse(true));
    }

    public record TranscribeResponse(String text) {
    }

    @PostMapping
    public ApiEnvelope<TranscribeResponse> transcribe(@RequestBody(required = false) byte[] audio) {
        if (!transcriber.enabled()) {
            throw ApiException.notFound("接口不存在");
        }
        if (audio == null || audio.length == 0) {
            throw ApiException.badRequest("音频不能为空");
        }
        if (audio.length > MAX_BYTES) {
            throw ApiException.badRequest("音频最大 10MB");
        }
        try {
            return ApiEnvelope.ok(new TranscribeResponse(transcriber.transcribe(audio)));
        } catch (IOException e) {
            throw new ApiException(502, "语音识别失败，请稍后重试或改用浏览器识别");
        }
    }
}
