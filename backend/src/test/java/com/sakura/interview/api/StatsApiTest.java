package com.sakura.interview.api;

import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 统计与管理端切片测试：真路由真拦截器真信封，领域依赖内存假件。
 * 覆盖登录态隔离、AdminGate 三态（未配置/非管理员/管理员）、题库上传的校验与幂等口径。
 */
@WebMvcTest(controllers = {AuthController.class, StatsController.class})
class StatsApiTest {

    /** 门禁状态按用例切换：静态字段供 TestBeans 读取。 */
    private static final AtomicReference<String> adminName = new AtomicReference<>("");

    private static final FakeUserStore users = new FakeUserStore();
    private static final FakeAuthStore authSessions = new FakeAuthStore();
    private static final FakeStatsStore stats = new FakeStatsStore();
    private static final FakeBankImporter importer = new FakeBankImporter();

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
        StatsStore statsStore() {
            return stats;
        }

        @Bean
        QuestionBankImportPort bankImporter() {
            return importer;
        }

        @Bean
        AdminGate adminGate() {
            // 上下文跨用例缓存，record 会把初值定格——lambda 读静态引用保持每用例可切换
            return username -> !adminName.get().isEmpty() && adminName.get().equals(username);
        }
    }

    @Autowired
    private MockMvc mockMvc;

    @BeforeEach
    void reset() {
        adminName.set("");
        importer.reset();
    }

    @Test
    void 未登录访问统计被拦截401() throws Exception {
        mockMvc.perform(get("/api/stats/tokens")).andExpect(status().isUnauthorized());
    }

    @Test
    void 登录后拿到自己的汇总趋势与会话明细() throws Exception {
        Cookie cookie = sessionOf("alice");
        long uid = users.byUsername.get("alice").id();
        stats.summaries.put(uid, new StatsStore.TokenSummary(1000, 200, 3, 12));
        stats.trends.put(uid, List.of(new StatsStore.DailyPoint("2026-11-03", 1200)));
        stats.sessionsByUser.put(uid, List.of(
                new StatsStore.SessionRow("s-1", "Java后端", "MEDIUM", "2026-11-03 19:44", 12, 1200)));

        mockMvc.perform(get("/api/stats/tokens").cookie(cookie))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.summary.totalTokens").value(1200))
                .andExpect(jsonPath("$.data.summary.inputTokens").value(1000))
                .andExpect(jsonPath("$.data.daily[0].date").value("2026-11-03"))
                .andExpect(jsonPath("$.data.sessions[0].sessionId").value("s-1"));
    }

    @Test
    void 管理门禁未配置时接口404零暴露() throws Exception {
        sessionOf("boss");
        mockMvc.perform(get("/api/stats/admin/overview").cookie(sessionOf("boss")))
                .andExpect(status().isNotFound());
    }

    @Test
    void 非管理员访问管理接口404() throws Exception {
        adminName.set("boss");
        sessionOf("boss");
        mockMvc.perform(get("/api/stats/admin/overview").cookie(sessionOf("alice")))
                .andExpect(status().isNotFound());
    }

    @Test
    void 管理员拿到全站汇总与用户排行() throws Exception {
        adminName.set("boss");
        sessionOf("boss");
        stats.site = new StatsStore.TokenSummary(5000, 900, 8, 40);
        stats.users.add(new StatsStore.UserRow("alice", 3, 4000));

        mockMvc.perform(get("/api/stats/admin/overview").cookie(sessionOf("boss")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.site.totalTokens").value(5900))
                .andExpect(jsonPath("$.data.users[0].username").value("alice"));
    }

    @Test
    void 管理员上传题库返回解析入库与跳过数() throws Exception {
        adminName.set("boss");
        sessionOf("boss");
        importer.next = new QuestionBankImportPort.ImportResult(30, 18);

        MockMultipartFile file = new MockMultipartFile(
                "file", "bank.md", "text/markdown", "## MQ-001\n题干：x".getBytes());

        mockMvc.perform(multipart("/api/stats/admin/question-bank")
                        .file(file).cookie(sessionOf("boss")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.parsed").value(30))
                .andExpect(jsonPath("$.data.imported").value(18))
                .andExpect(jsonPath("$.data.skipped").value(12));
        assertThat(importer.lastContent).contains("MQ-001");
    }

    @Test
    void 题库内容不合法返回400带原因() throws Exception {
        adminName.set("boss");
        sessionOf("boss");
        importer.nextFailure = "题目字段不完整：MQ-002";

        MockMultipartFile file = new MockMultipartFile(
                "file", "bank.md", "text/markdown", "## MQ-002\n题干：x".getBytes());

        mockMvc.perform(multipart("/api/stats/admin/question-bank")
                        .file(file).cookie(sessionOf("boss")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("题目字段不完整：MQ-002"));
    }

    @Test
    void 空文件与非管理员上传分别400与404() throws Exception {
        adminName.set("boss");
        sessionOf("boss");
        MockMultipartFile empty = new MockMultipartFile(
                "file", "bank.md", "text/markdown", new byte[0]);

        mockMvc.perform(multipart("/api/stats/admin/question-bank")
                        .file(empty).cookie(sessionOf("boss")))
                .andExpect(status().isBadRequest());

        MockMultipartFile file = new MockMultipartFile(
                "file", "bank.md", "text/markdown", "## MQ-001\n题干：x".getBytes());
        mockMvc.perform(multipart("/api/stats/admin/question-bank")
                        .file(file).cookie(sessionOf("alice")))
                .andExpect(status().isNotFound());
    }

    /** 幂等取会话 Cookie：用户名已占用（静态假件跨用例复用）时直接走登录。 */
    private Cookie sessionOf(String username) throws Exception {
        mockMvc.perform(post("/api/auth/register")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"username\":\"" + username + "\",\"password\":\"password123\"}"))
                .andReturn();
        MvcResult result = mockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"" + username + "\",\"password\":\"password123\"}"))
                .andExpect(status().isOk())
                .andReturn();
        jakarta.servlet.http.Cookie[] cookies = result.getResponse().getCookies();
        assertThat(cookies).isNotEmpty();
        return cookies[0];
    }

    /** 导入端口内存假件：结果与失败可预置，记录最近一次收到的内容。 */
    private static final class FakeBankImporter implements QuestionBankImportPort {

        ImportResult next = new ImportResult(0, 0);
        String nextFailure = "";
        String lastContent = "";

        void reset() {
            next = new ImportResult(0, 0);
            nextFailure = "";
            lastContent = "";
        }

        @Override
        public ImportResult importText(String content) {
            this.lastContent = content;
            if (!nextFailure.isEmpty()) {
                throw new IllegalArgumentException(nextFailure);
            }
            return next;
        }
    }
}
