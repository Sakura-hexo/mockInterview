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

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 服务端 TTS 切片测试：真路由真拦截器，合成器用内存假件。
 * 覆盖鉴权、嗓音清单、合成与缓存命中、文本校验、未启用 404 回落语义。
 */
@WebMvcTest(controllers = {AuthController.class, TtsController.class})
class TtsApiTest {

    private static final FakeUserStore users = new FakeUserStore();
    private static final FakeAuthStore authSessions = new FakeAuthStore();
    private static final FakeTtsSpeaker speaker = new FakeTtsSpeaker();

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
        TtsSpeaker ttsSpeaker() {
            return speaker;
        }
    }

    @Autowired
    private MockMvc mockMvc;

    @BeforeEach
    void reset() {
        speaker.reset();
    }

    @Test
    void 未登录合成被拦截401() throws Exception {
        mockMvc.perform(post("/api/tts")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"text\":\"你好\"}"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void 嗓音清单接口返回可用清单() throws Exception {
        mockMvc.perform(get("/api/tts/voices").cookie(sessionOf("ttsuser")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.voices[0]").value("zh-CN-XiaoxiaoNeural"))
                .andExpect(jsonPath("$.data.voices.length()").value(2));
    }

    @Test
    void 合成返回mp3字节且未指定嗓音用默认() throws Exception {
        MvcResult result = mockMvc.perform(post("/api/tts")
                        .cookie(sessionOf("ttsuser"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"text\":\"第一段测试文本\"}"))
                .andExpect(status().isOk())
                .andReturn();

        assertThat(result.getResponse().getContentType()).startsWith("audio/mpeg");
        assertThat(new String(result.getResponse().getContentAsByteArray(), StandardCharsets.UTF_8))
                .isEqualTo("mp3:zh-CN-XiaoxiaoNeural:第一段测试文本");
        assertThat(speaker.calls).isEqualTo(1);
    }

    @Test
    void 指定清单内嗓音透传给合成器() throws Exception {
        MvcResult result = mockMvc.perform(post("/api/tts")
                        .cookie(sessionOf("ttsuser"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"text\":\"云希嗓音\",\"voice\":\"zh-CN-YunxiNeural\"}"))
                .andExpect(status().isOk())
                .andReturn();

        assertThat(new String(result.getResponse().getContentAsByteArray(), StandardCharsets.UTF_8))
                .startsWith("mp3:zh-CN-YunxiNeural:");
    }

    @Test
    void 同文本同嗓音命中缓存不重复合成() throws Exception {
        Cookie cookie = sessionOf("ttsuser");
        String body = "{\"text\":\"缓存命中专用文本\",\"voice\":\"zh-CN-YunxiNeural\"}";
        mockMvc.perform(post("/api/tts").cookie(cookie)
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk());
        mockMvc.perform(post("/api/tts").cookie(cookie)
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk());

        assertThat(speaker.calls).isEqualTo(1);
    }

    @Test
    void 空文本与超长文本返回400() throws Exception {
        Cookie cookie = sessionOf("ttsuser");
        mockMvc.perform(post("/api/tts").cookie(cookie)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"text\":\"   \"}"))
                .andExpect(status().isBadRequest());
        mockMvc.perform(post("/api/tts").cookie(cookie)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"text\":\"" + "长".repeat(601) + "\"}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void 未启用时双接口404前端据此回落() throws Exception {
        speaker.voicesRef.set(List.of());
        Cookie cookie = sessionOf("ttsuser");
        mockMvc.perform(get("/api/tts/voices").cookie(cookie))
                .andExpect(status().isNotFound());
        mockMvc.perform(post("/api/tts").cookie(cookie)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"text\":\"你好\"}"))
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

    /** 合成假件：计数合成次数，voices 可按用例清空模拟未启用。 */
    static final class FakeTtsSpeaker implements TtsSpeaker {

        final AtomicReference<List<String>> voicesRef =
                new AtomicReference<>(List.of("zh-CN-XiaoxiaoNeural", "zh-CN-YunxiNeural"));
        int calls;

        void reset() {
            calls = 0;
            voicesRef.set(List.of("zh-CN-XiaoxiaoNeural", "zh-CN-YunxiNeural"));
        }

        @Override
        public List<String> voices() {
            return voicesRef.get();
        }

        @Override
        public byte[] synthesize(String text, String voice) {
            calls += 1;
            return ("mp3:" + voice + ":" + text).getBytes(StandardCharsets.UTF_8);
        }
    }
}
