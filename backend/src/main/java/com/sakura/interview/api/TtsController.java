package com.sakura.interview.api;

import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.io.IOException;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 服务端 TTS 接口：GET /api/tts/voices 探测可用性兼嗓音清单（空配置 404，
 * 前端据此回落浏览器朗读）；POST /api/tts 合成 wav，同文本同嗓音走 LRU 缓存
 * 免重复请求（读题按钮反复点、回看重读都是重复合成）。
 */
@RestController
@RequestMapping("/api/tts")
public final class TtsController {

    private static final int MAX_TEXT = 600;
    private static final int MAX_CACHE_ENTRIES = 100;

    private final TtsSpeaker speaker;
    private final Map<String, byte[]> cache = Collections.synchronizedMap(
            new LinkedHashMap<>(16, 0.75f, true) {
                @Override
                protected boolean removeEldestEntry(Map.Entry<String, byte[]> eldest) {
                    return size() > MAX_CACHE_ENTRIES;
                }
            });

    public TtsController(TtsSpeaker speaker) {
        this.speaker = speaker;
    }

    public record VoicesResponse(List<String> voices) {
    }

    @GetMapping("/voices")
    public ApiEnvelope<VoicesResponse> voices() {
        List<String> voices = speaker.voices();
        if (voices.isEmpty()) {
            throw ApiException.notFound("接口不存在");
        }
        return ApiEnvelope.ok(new VoicesResponse(voices));
    }

    public record SynthesizeRequest(String text, String voice) {
    }

    @PostMapping
    public ResponseEntity<byte[]> synthesize(@RequestBody SynthesizeRequest request) throws IOException {
        List<String> voices = speaker.voices();
        if (voices.isEmpty()) {
            throw ApiException.notFound("接口不存在");
        }
        String text = request.text() == null ? "" : request.text().trim();
        if (text.isEmpty()) {
            throw ApiException.badRequest("文本不能为空");
        }
        if (text.length() > MAX_TEXT) {
            throw ApiException.badRequest("文本最长 " + MAX_TEXT + " 字");
        }
        String voice = request.voice() == null || !voices.contains(request.voice())
                ? voices.get(0)
                : request.voice();

        String key = voice + '\n' + text;
        byte[] audio = cache.get(key);
        if (audio == null) {
            try {
                audio = speaker.synthesize(text, voice);
            } catch (IOException e) {
                throw new ApiException(502, "语音合成失败，请稍后重试或改用浏览器朗读");
            }
            cache.put(key, audio);
        }
        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType("audio/wav"))
                .contentLength(audio.length)
                .body(audio);
    }
}
