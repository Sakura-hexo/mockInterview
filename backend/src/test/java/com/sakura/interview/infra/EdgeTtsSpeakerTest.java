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

/** 边车客户端测试：内嵌 HTTP 假服务验证透传与错误转译，不碰微软真通道。 */
class EdgeTtsSpeakerTest {

    @Test
    void 合成透传文本与嗓音并返回音频字节() throws Exception {
        List<String> bodies = new CopyOnWriteArrayList<>();
        HttpServer server = fakeServer(200, new byte[]{0x01, 0x02}, bodies);
        server.start();
        try {
            EdgeTtsSpeaker speaker = new EdgeTtsSpeaker("http://localhost:" + server.getAddress().getPort() + "/");
            byte[] audio = speaker.synthesize("你好", "zh-CN-YunxiNeural");

            assertThat(audio).containsExactly(0x01, 0x02);
            assertThat(bodies).hasSize(1);
            assertThat(bodies.get(0)).contains("你好").contains("zh-CN-YunxiNeural");
            assertThat(speaker.voices()).first().isEqualTo("zh-CN-XiaoxiaoNeural");
        } finally {
            server.stop(0);
        }
    }

    @Test
    void 边车异常状态转IOException带状态码() throws Exception {
        HttpServer server = fakeServer(502, new byte[0], new CopyOnWriteArrayList<>());
        server.start();
        try {
            EdgeTtsSpeaker speaker = new EdgeTtsSpeaker("http://localhost:" + server.getAddress().getPort());
            assertThatThrownBy(() -> speaker.synthesize("你好", "zh-CN-XiaoxiaoNeural"))
                    .isInstanceOf(IOException.class)
                    .hasMessageContaining("502");
        } finally {
            server.stop(0);
        }
    }

    private static HttpServer fakeServer(int status, byte[] audio, List<String> bodies) throws IOException {
        HttpServer server = HttpServer.create(new InetSocketAddress(0), 0);
        server.createContext("/synthesize", exchange -> {
            bodies.add(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            exchange.getResponseHeaders().add("Content-Type", "audio/mpeg");
            exchange.sendResponseHeaders(status, audio.length);
            if (audio.length > 0) {
                exchange.getResponseBody().write(audio);
            }
            exchange.close();
        });
        return server;
    }
}
