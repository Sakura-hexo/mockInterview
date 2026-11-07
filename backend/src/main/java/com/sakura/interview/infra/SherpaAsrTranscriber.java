package com.sakura.interview.infra;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.sakura.interview.api.AsrTranscriber;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

/**
 * 经 asr 边车识别：paraformer 模型推理依赖（sherpa-onnx + ffmpeg + 模型权重）
 * 全部封在独立容器里，本类保持纯 HTTP；换模型只改边车镜像，Java 侧不动。
 */
public final class SherpaAsrTranscriber implements AsrTranscriber {

    private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(5);
    private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(60);

    private final HttpClient http;
    private final URI transcribeUri;
    private final Gson gson = new Gson();

    public SherpaAsrTranscriber(String baseUrl) {
        this.http = HttpClient.newBuilder().connectTimeout(CONNECT_TIMEOUT).build();
        String trimmed = baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl;
        this.transcribeUri = URI.create(trimmed + "/transcribe");
    }

    @Override
    public boolean enabled() {
        return true;
    }

    @Override
    public String transcribe(byte[] audio) throws IOException {
        HttpRequest request = HttpRequest.newBuilder(transcribeUri)
                .timeout(REQUEST_TIMEOUT)
                .header("Content-Type", "application/octet-stream")
                .POST(HttpRequest.BodyPublishers.ofByteArray(audio))
                .build();
        HttpResponse<byte[]> response;
        try {
            response = http.send(request, HttpResponse.BodyHandlers.ofByteArray());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("asr 调用被中断", e);
        }
        if (response.statusCode() != 200) {
            throw new IOException("asr 服务返回 " + response.statusCode());
        }
        JsonObject body;
        try {
            body = gson.fromJson(new String(response.body(), java.nio.charset.StandardCharsets.UTF_8),
                    JsonObject.class);
        } catch (com.google.gson.JsonSyntaxException e) {
            throw new IOException("asr 响应解析失败", e);
        }
        if (body == null || body.get("text") == null) {
            throw new IOException("asr 响应缺少 text 字段");
        }
        return body.get("text").getAsString();
    }
}
