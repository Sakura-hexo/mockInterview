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
 * 经 edge-tts 边车合成：微软 Edge 朗读通道的协议（wss + v2 鉴权）由官方 Python
 * 库封装在独立容器里，鉴权轮换只需升级边车镜像的库版本，本类保持纯 HTTP。
 */
public final class EdgeTtsSpeaker implements TtsSpeaker {

    public static final List<String> VOICES = List.of(
            "zh-CN-XiaoxiaoNeural", "zh-CN-YunxiNeural", "zh-CN-YunyangNeural");

    private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(5);
    private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(30);

    private final HttpClient http;
    private final URI synthesizeUri;

    public EdgeTtsSpeaker(String baseUrl) {
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
            throw new IOException("edge-tts 调用被中断", e);
        }
        if (response.statusCode() != 200) {
            throw new IOException("edge-tts 服务返回 " + response.statusCode());
        }
        return response.body();
    }
}
