package com.sakura.interview.infra;

import com.google.gson.JsonObject;
import com.sakura.interview.api.TtsSpeaker;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;

/**
 * 经 TTS 边车合成：本地 matcha 模型（sherpa-onnx）封装在独立容器里，
 * 换引擎只升级边车镜像，本类保持纯 HTTP。换装自微软 edge-tts 通道：
 * 到微软链路晚高峰吞吐仅约 300kbps，189 字要等 6 秒；本地合成 RTF 0.03，
 * 同文本约 1 秒出声且不受网络波动影响（选型记录见 .agentdocs）。
 */
public final class SherpaTtsSpeaker implements TtsSpeaker {

    /** 单说话人模型，清单一项；前端下拉与缓存键天然兼容。 */
    public static final List<String> VOICES = List.of("matcha-zh-en");

    private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(5);
    private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(30);

    private final HttpClient http;
    private final URI synthesizeUri;

    public SherpaTtsSpeaker(String baseUrl) {
        this.http = HttpClient.newBuilder().connectTimeout(CONNECT_TIMEOUT).build();
        String trimmed = baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl;
        this.synthesizeUri = URI.create(trimmed + "/synthesize");
    }

    @Override
    public List<String> voices() {
        return VOICES;
    }

    @Override
    public byte[] synthesize(String text, String voice) throws IOException {
        JsonObject body = new JsonObject();
        body.addProperty("text", text);
        body.addProperty("voice", voice);
        HttpRequest request = HttpRequest.newBuilder(synthesizeUri)
                .timeout(REQUEST_TIMEOUT)
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body.toString()))
                .build();
        HttpResponse<byte[]> response;
        try {
            response = http.send(request, HttpResponse.BodyHandlers.ofByteArray());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("TTS 边车调用被中断", e);
        }
        if (response.statusCode() != 200) {
            throw new IOException("TTS 边车服务返回 " + response.statusCode());
        }
        return response.body();
    }
}
