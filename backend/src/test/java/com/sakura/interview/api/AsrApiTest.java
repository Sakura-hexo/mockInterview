package com.sakura.interview.api;

import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 服务端 ASR 切片测试：真路由真拦截器，识别器用内存假件。
 * 覆盖鉴权、健康探测、识别返回文本、大小校验、边车故障降级、未启用 404 回落语义。
 */
@WebMvcTest(controllers = {AuthController.class, AsrController.class})
class AsrApiTest {

    private static final FakeUserStore users = new FakeUserStore();
    private static final FakeAuthStore authSessions = new FakeAuthStore();
    private static final FakeAsrTranscriber transcriber = new FakeAsrTranscriber();

    @TestConfiguration
    static class TestBeans {

        @Bean
        AuthService authService() {
            return new AuthService(users, authSessions);
        }

        @Bean
        UserStore userStore() {
            return users;
        }

        @Bean
        AuthInterceptor authInterceptor(AuthService authService) {
            return new AuthInterceptor(authSessions);
        }

        @Bean
        AsrTranscriber asrTranscriber() {
            return transcriber;
        }
    }

    @Autowired
    private MockMvc mockMvc;

    @BeforeEach
    void reset() {
        transcriber.reset();
    }

    @Test
    void 未登录识别被拦截401() throws Exception {
        mockMvc.perform(post("/api/asr")
                        .contentType(MediaType.APPLICATION_OCTET_STREAM)
                        .content(new byte[16]))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void 健康探测返回启用状态() throws Exception {
        mockMvc.perform(get("/api/asr/health").cookie(sessionOf("asruser")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.enabled").value(true));
    }

    @Test
    void 识别返回文本并透传音频字节() throws Exception {
        MvcResult result = mockMvc.perform(post("/api/asr")
                        .cookie(sessionOf("asruser"))
                        .contentType(MediaType.APPLICATION_OCTET_STREAM)
                        .content(new byte[]{1, 2, 3, 4}))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.text").value("识别出的中文文本"))
                .andReturn();

        assertThat(transcriber.calls).isEqualTo(1);
        assertThat(transcriber.lastAudioLength).isEqualTo(4);
        assertThat(result.getResponse().getContentType()).startsWith("application/json");
    }

    @Test
    void 空音频与超限音频返回400() throws Exception {
        Cookie cookie = sessionOf("asruser");
        mockMvc.perform(post("/api/asr").cookie(cookie)
                        .contentType(MediaType.APPLICATION_OCTET_STREAM)
                        .content(new byte[0]))
                .andExpect(status().isBadRequest());
        mockMvc.perform(post("/api/asr").cookie(cookie)
                        .contentType(MediaType.APPLICATION_OCTET_STREAM)
                        .content(new byte[10 * 1024 * 1024 + 1]))
                .andExpect(status().isBadRequest());
        assertThat(transcriber.calls).isZero();
    }

    @Test
    void 边车故障转502带回退提示() throws Exception {
        transcriber.fail = true;
        mockMvc.perform(post("/api/asr").cookie(sessionOf("asruser"))
                        .contentType(MediaType.APPLICATION_OCTET_STREAM)
                        .content(new byte[16]))
                .andExpect(status().is(502));
    }

    @Test
    void 未启用时双接口404前端据此回落() throws Exception {
        transcriber.enabled = false;
        Cookie cookie = sessionOf("asruser");
        mockMvc.perform(get("/api/asr/health").cookie(cookie))
                .andExpect(status().isNotFound());
        mockMvc.perform(post("/api/asr").cookie(cookie)
                        .contentType(MediaType.APPLICATION_OCTET_STREAM)
                        .content(new byte[16]))
                .andExpect(status().isNotFound());
    }

    private Cookie sessionOf(String username) throws Exception {
        mockMvc.perform(post("/api/auth/register")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"username\":\"" + username + "\",\"password\":\"password123\"}"))
                .andReturn();
        MvcResult login = mockMvc.perform(post("/api/auth/login")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"username\":\"" + username + "\",\"password\":\"password123\"}"))
                .andExpect(status().isOk())
                .andReturn();
        return login.getResponse().getCookies()[0];
    }

    /** 识别假件：计数调用并记录音频长度，可按用例切故障与未启用。 */
    static final class FakeAsrTranscriber implements AsrTranscriber {

        boolean enabled = true;
        boolean fail;
        int calls;
        int lastAudioLength;

        void reset() {
            enabled = true;
            fail = false;
            calls = 0;
            lastAudioLength = -1;
        }

        @Override
        public boolean enabled() {
            return enabled;
        }

        @Override
        public String transcribe(byte[] audio) throws IOException {
            if (fail) {
                throw new IOException("边车不可达");
            }
            calls += 1;
            lastAudioLength = audio.length;
            return "识别出的中文文本";
        }
    }
}
