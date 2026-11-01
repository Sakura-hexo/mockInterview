package com.sakura.interview.api;

import com.jayway.jsonpath.JsonPath;
import com.sakura.interview.agent.FakeAgentRuntime;
import com.sakura.interview.agent.Supervisor;
import com.sakura.interview.infra.LlmCaller;
import com.sakura.interview.infra.StructuredOutputInvoker;
import com.sakura.interview.session.ContextCompressor;
import com.sakura.interview.session.FakeReportStore;
import com.sakura.interview.session.FakeSessionStore;
import com.sakura.interview.session.ReportService;
import com.sakura.interview.session.ReportTask;
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

import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.asyncDispatch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.request;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Web 层切片集成测试：真路由、真拦截器、真信封，领域依赖全部落在内存假件上。
 * 覆盖注册登录令牌链路、401 拦截、按用户隔离、状态机冲突、SSE 事件流与报告 202。
 */
@WebMvcTest(controllers = {AuthController.class, InterviewController.class})
class InterviewApiTest {

    private static final String JD_JSON =
            "{\"direction\":\"Java后端\",\"skills\":[\"Spring\",\"MySQL\",\"Redis\"],\"bonus\":[]}";
    private static final String CREATE_BODY = """
            {"resumeText":"三年 Java 后端","jdText":"招 Java 后端","config":
            {"direction":"JAVA_BACKEND","difficulty":"MEDIUM","questionCount":5}}""";

    private static final FakeSessionStore sessions = new FakeSessionStore();
    private static final FakeReportStore reports = new FakeReportStore();
    private static final FakeAuthStore authSessions = new FakeAuthStore();
    private static final FakeUserStore users = new FakeUserStore();
    private static final FakeAgentRuntime runtime = new FakeAgentRuntime(
            c -> "请介绍线程池的核心参数。\n选题");

    @TestConfiguration
    static class TestBeans {

        @Bean
        com.sakura.interview.session.SessionStore sessionStore() {
            return sessions;
        }

        @Bean
        com.sakura.interview.session.ReportStore reportStore() {
            return reports;
        }

        @Bean
        AuthService authService() {
            return new AuthService(users, authSessions);
        }

        @Bean
        AuthInterceptor authInterceptor(AuthService authService) {
            return new AuthInterceptor(authSessions);
        }

        @Bean
        Supervisor supervisor() {
            return new Supervisor(runtime, new StructuredOutputInvoker(branchingCaller()),
                    sessions, new ContextCompressor((sys, user) -> "合并摘要"),
                    new ReportService(sessions, reports));
        }

        @Bean
        ChatStreamService chatStreamService(Supervisor supervisor) {
            return new ChatStreamService(supervisor, sessions, Runnable::run);
        }
    }

    private static LlmCaller branchingCaller() {
        return (sys, user) -> {
            if (sys.contains("简历分析器")) {
                return "{\"digest\":\"三年 Java 后端，主导订单系统\"}";
            }
            if (sys.contains("意图分类器")) {
                return "{\"intent\":\"INTERVIEW\"}";
            }
            return JD_JSON;
        };
    }

    @Autowired
    private MockMvc mockMvc;

    @BeforeEach
    void resetFixtures() {
        sessions.sessions.clear();
        sessions.messages.clear();
        sessions.summaries.clear();
        sessions.endedSessions.clear();
        sessions.statuses.clear();
        reports.tasks.clear();
        authSessions.sessions.clear();
        users.byUsername.clear();
    }

    private String register(String username) throws Exception {
        MvcResult result = mockMvc.perform(post("/api/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"" + username + "\",\"password\":\"password123\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data.username").value(username))
                .andReturn();
        return JsonPath.read(result.getResponse().getContentAsString(StandardCharsets.UTF_8),
                "$.data.token");
    }

    private String createSession(String token) throws Exception {
        MvcResult result = mockMvc.perform(post("/api/interviews")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(CREATE_BODY))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.resumeDigest").value("三年 Java 后端，主导订单系统"))
                .andReturn();
        return JsonPath.read(result.getResponse().getContentAsString(StandardCharsets.UTF_8),
                "$.data.sessionId");
    }

    @Test
    void 注册即登录且令牌可访问受保护接口() throws Exception {
        String token = register("tester");

        mockMvc.perform(get("/api/interviews").header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data.total").value(0));
    }

    @Test
    void 未认证访问返回401信封() throws Exception {
        mockMvc.perform(get("/api/interviews"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value(401))
                .andExpect(jsonPath("$.message").value("未认证或会话已过期"));
    }

    @Test
    void 登出后令牌失效() throws Exception {
        String token = register("tester");
        mockMvc.perform(post("/api/auth/logout").header("Authorization", "Bearer " + token))
                .andExpect(status().isOk());

        mockMvc.perform(get("/api/interviews").header("Authorization", "Bearer " + token))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void 开始面试返回固定开场白并落为首题() throws Exception {
        String token = register("tester");
        String sessionId = createSession(token);

        mockMvc.perform(post("/api/interviews/" + sessionId + "/start")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.firstQuestion").value(
                        "面试正式开始。请先做一个简短的自我介绍，重点讲讲与目标岗位相关的技能与项目经历。"))
                .andExpect(jsonPath("$.data.focusPoints[0]").value("Spring"));

        mockMvc.perform(get("/api/interviews/" + sessionId)
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.session.status").value("IN_PROGRESS"))
                .andExpect(jsonPath("$.data.session.questionCount").value(5))
                .andExpect(jsonPath("$.data.messages[0].role").value("INTERVIEWER"))
                .andExpect(jsonPath("$.data.messages[0].action").value("QUESTION"));
    }

    @Test
    void 重复开始与未开始结束返回409() throws Exception {
        String token = register("tester");
        String sessionId = createSession(token);

        mockMvc.perform(post("/api/interviews/" + sessionId + "/start")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk());
        mockMvc.perform(post("/api/interviews/" + sessionId + "/start")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value(409));

        // 已开始的会话可结束，再建新会话直接结束则 409
        mockMvc.perform(post("/api/interviews/" + sessionId + "/end")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk());
        String fresh = createSession(token);
        mockMvc.perform(post("/api/interviews/" + fresh + "/end")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isConflict());
    }

    @Test
    void 他人会话一律404不区分不存在() throws Exception {
        String owner = register("owner");
        String stranger = register("stranger");
        String sessionId = createSession(owner);

        mockMvc.perform(get("/api/interviews/" + sessionId)
                        .header("Authorization", "Bearer " + stranger))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value(404));
        mockMvc.perform(get("/api/interviews/s-none")
                        .header("Authorization", "Bearer " + stranger))
                .andExpect(status().isNotFound());
    }

    @Test
    void 会话列表只含本人会话() throws Exception {
        String owner = register("owner");
        String stranger = register("stranger");
        createSession(owner);
        createSession(owner);

        mockMvc.perform(get("/api/interviews")
                        .header("Authorization", "Bearer " + stranger))
                .andExpect(jsonPath("$.data.total").value(0));
        mockMvc.perform(get("/api/interviews")
                        .header("Authorization", "Bearer " + owner))
                .andExpect(jsonPath("$.data.total").value(2))
                .andExpect(jsonPath("$.data.list.length()").value(2));
    }

    @Test
    void 对话SSE按事件顺序推送() throws Exception {
        String token = register("tester");
        String sessionId = createSession(token);
        mockMvc.perform(post("/api/interviews/" + sessionId + "/start")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk());

        MvcResult started = mockMvc.perform(post("/api/interviews/" + sessionId + "/chat")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"content\":\"我先自我介绍\",\"images\":[]}"))
                .andExpect(request().asyncStarted())
                .andReturn();
        String body = mockMvc.perform(asyncDispatch(started))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);

        assertThat(body).contains("event:message").contains("event:action")
                .contains("event:metrics").contains("event:done");
        assertThat(body).contains("\"type\":\"QUESTION\"");
        assertThat(body.indexOf("event:message")).isLessThan(body.indexOf("event:action"));
        assertThat(body.indexOf("event:action")).isLessThan(body.indexOf("event:metrics"));
        assertThat(body.indexOf("event:metrics")).isLessThan(body.indexOf("event:done"));
    }

    @Test
    void 未开始的会话对话返回409() throws Exception {
        String token = register("tester");
        String sessionId = createSession(token);

        mockMvc.perform(post("/api/interviews/" + sessionId + "/chat")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"content\":\"你好\",\"images\":[]}"))
                .andExpect(status().isConflict());
    }

    @Test
    void 结束面试建报告任务且轮询返回202() throws Exception {
        String token = register("tester");
        String sessionId = createSession(token);
        mockMvc.perform(post("/api/interviews/" + sessionId + "/start")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk());
        // 补两轮问答使配对数达到报告下限
        sessions.appendMessage(sessionId, "user", "回答一", 0, 0);
        sessions.appendMessage(sessionId, "interviewer", "第二题", 0, 0, "NEXT");
        sessions.appendMessage(sessionId, "user", "回答二", 0, 0);

        mockMvc.perform(post("/api/interviews/" + sessionId + "/end")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.reportStatus").value("GENERATING"));

        mockMvc.perform(get("/api/interviews/" + sessionId + "/report")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(202))
                .andExpect(jsonPath("$.data.reportStatus").value("GENERATING"));

        // 任务完成后返回完整报告
        reports.tasks.put(sessionId, new ReportTask("r-done", sessionId, ReportTask.DONE, 0,
                "{\"sessionId\":\"" + sessionId + "\",\"scores\":{\"overall\":6,"
                        + "\"technicalDepth\":6,\"expression\":6,\"followUpHandling\":6},"
                        + "\"items\":[],\"suggestions\":[],\"tokenTotal\":100,\"durationMs\":1000}",
                null));
        mockMvc.perform(get("/api/interviews/" + sessionId + "/report")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data.scores.overall").value(6))
                .andExpect(jsonPath("$.data.tokenTotal").value(100));
    }

    @Test
    void 无报告任务时获取报告返回404() throws Exception {
        String token = register("tester");
        String sessionId = createSession(token);

        mockMvc.perform(get("/api/interviews/" + sessionId + "/report")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value(404));
    }

    @Test
    void multipart建会话与JSON口径一致() throws Exception {
        String token = register("tester");
        byte[] resume = "三年 Java 后端".getBytes(StandardCharsets.UTF_8);

        mockMvc.perform(multipart("/api/interviews")
                        .file(new MockMultipartFile(
                                "resumeFile", "resume.txt", "text/plain", resume))
                        .header("Authorization", "Bearer " + token)
                        .param("jdText", "招 Java 后端")
                        .param("config",
                                "{\"direction\":\"JAVA_BACKEND\",\"difficulty\":\"EASY\",\"questionCount\":3}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.resumeDigest").value("三年 Java 后端，主导订单系统"));
    }
}
