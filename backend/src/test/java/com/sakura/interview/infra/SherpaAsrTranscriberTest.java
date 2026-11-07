package com.sakura.interview.infra;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** ASR 边车客户端测试：内嵌 HTTP 假服务验证透传与错误转译，不加载真模型。 */
class SherpaAsrTranscriberTest {

    @Test
    void 识别透传音频字节并解析文本() throws Exception {
        List<byte[]> bodies = new CopyOnWriteArrayList<>();
        HttpServer server = fakeServer(200, "{\"text\":\"你好面试官\"}".getBytes(StandardCharsets.UTF_8), bodies);
        server.start();
        try {
            SherpaAsrTranscriber transcriber =
                    new SherpaAsrTranscriber("http://localhost:" + server.getAddress().getPort() + "/");
            assertThat(transcriber.enabled()).isTrue();
            assertThat(transcriber.transcribe(new byte[]{9, 8, 7})).isEqualTo("你好面试官");
            assertThat(bodies).hasSize(1);
            assertThat(bodies.get(0)).containsExactly(9, 8, 7);
        } finally {
            server.stop(0);
        }
    }

    @Test
    void 边车异常状态与缺字段转IOException() throws Exception {
        HttpServer server = fakeServer(502, new byte[0], new CopyOnWriteArrayList<>());
        server.start();
        try {
            SherpaAsrTranscriber transcriber =
                    new SherpaAsrTranscriber("http://localhost:" + server.getAddress().getPort());
            assertThatThrownBy(() -> transcriber.transcribe(new byte[4]))
                    .isInstanceOf(IOException.class)
                    .hasMessageContaining("502");
        } finally {
            server.stop(0);
        }
        HttpServer broken = fakeServer(200, "{\"foo\":1}".getBytes(StandardCharsets.UTF_8), new CopyOnWriteArrayList<>());
        broken.start();
        try {
            SherpaAsrTranscriber transcriber =
                    new SherpaAsrTranscriber("http://localhost:" + broken.getAddress().getPort());
            assertThatThrownBy(() -> transcriber.transcribe(new byte[4]))
                    .isInstanceOf(IOException.class)
                    .hasMessageContaining("text");
        } finally {
            broken.stop(0);
        }
    }

    private static HttpServer fakeServer(int status, byte[] payload, List<byte[]> bodies) throws IOException {
        HttpServer server = HttpServer.create(new InetSocketAddress(0), 0);
        server.createContext("/transcribe", exchange -> {
            bodies.add(exchange.getRequestBody().readAllBytes());
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(status, payload.length == 0 ? -1 : payload.length);
            if (payload.length > 0) {
                exchange.getResponseBody().write(payload);
            }
            exchange.close();
        });
        return server;
    }
}
