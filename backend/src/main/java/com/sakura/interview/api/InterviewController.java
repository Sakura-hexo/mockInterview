package com.sakura.interview.api;

import com.google.gson.Gson;
import com.sakura.interview.agent.Supervisor;
import com.sakura.interview.session.QaPair;
import com.sakura.interview.session.ReportGenerator;
import com.sakura.interview.session.ReportStore;
import com.sakura.interview.session.ReportTask;
import com.sakura.interview.session.InterviewReport;
import com.sakura.interview.session.SessionConfig;
import com.sakura.interview.session.SessionOverview;
import com.sakura.interview.session.SessionState;
import com.sakura.interview.session.SessionStore;
import com.sakura.interview.session.StoredMessage;
import com.sakura.interview.tool.ResumeParseTool;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestAttribute;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.List;
import java.util.Set;

/**
 * 面试接口（接口文档 4.1 到 4.7）：全部按当前 userId 过滤，
 * 他人的会话与不存在的会话一律 404 不区分。开场白固定文案不调模型，
 * 结束与报告幂等；报告生成中返回信封 code 202。
 */
@RestController
@RequestMapping("/api/interviews")
public final class InterviewController {

    private static final int MIN_PAIRS = 2;
    private static final int MAX_JD_CHARS = 20_000;
    private static final int MAX_RESUME_TEXT_CHARS = 200_000;
    private static final int MAX_CONTENT_CHARS = 8_000;
    private static final int MAX_PAGE_SIZE = 50;
    private static final int MAX_FOCUS_POINTS = 5;
    private static final Set<String> DIFFICULTIES = Set.of("EASY", "MEDIUM", "HARD");
    private static final String OPENER =
            "面试正式开始。请先做一个简短的自我介绍，重点讲讲与目标岗位相关的技能与项目经历。";

    private final Supervisor supervisor;
    private final SessionStore store;
    private final ReportStore reportStore;
    private final ChatStreamService chatStream;
    private final ResumeParseTool resumeParser = new ResumeParseTool();
    private final Gson gson = new Gson();

    public InterviewController(Supervisor supervisor, SessionStore store,
                               ReportStore reportStore, ChatStreamService chatStream) {
        this.supervisor = supervisor;
        this.store = store;
        this.reportStore = reportStore;
        this.chatStream = chatStream;
    }

    /** 4.1 新建面试：JSON 带纯文本简历。 */
    @PostMapping(consumes = MediaType.APPLICATION_JSON_VALUE)
    public ApiEnvelope<InterviewDtos.CreateResponse> createFromText(
            @RequestAttribute(AuthInterceptor.USER_ID_ATTRIBUTE) Long userId,
            @RequestBody InterviewDtos.CreateRequest body) {
        String resumeText = requireResumeText(body.resumeText());
        return ApiEnvelope.ok(create(userId, resumeText, body.jdText(), body.config()));
    }

    /** 4.1 新建面试：multipart 带 PDF 简历文件。 */
    @PostMapping(consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ApiEnvelope<InterviewDtos.CreateResponse> createFromFile(
            @RequestAttribute(AuthInterceptor.USER_ID_ATTRIBUTE) Long userId,
            @RequestParam("resumeFile") MultipartFile resumeFile,
            @RequestParam("jdText") String jdText,
            @RequestParam("config") String configJson) throws java.io.IOException {
        String resumeText = resumeParser.extract(resumeFile.getBytes());
        return ApiEnvelope.ok(create(userId, resumeText, jdText, parseConfig(configJson)));
    }

    /** 4.2 开始面试：CREATED 才可用，开场白落库为首题气泡，刷新可还原。 */
    @PostMapping("/{sessionId}/start")
    public ApiEnvelope<InterviewDtos.StartResponse> start(
            @RequestAttribute(AuthInterceptor.USER_ID_ATTRIBUTE) Long userId,
            @PathVariable String sessionId) {
        SessionState state = owned(userId, sessionId);
        // 条件更新挡住双击并发：只有 CREATED 能推进到 IN_PROGRESS
        if (!store.casStatus(sessionId, SessionState.CREATED, SessionState.IN_PROGRESS)) {
            throw ApiException.conflict("面试已开始或已结束，无法重复开始");
        }
        store.appendMessage(sessionId, "interviewer", OPENER, 0, 0, Supervisor.ACTION_QUESTION);
        List<String> focusPoints = state.jdProfile().skills().stream()
                .limit(MAX_FOCUS_POINTS).toList();
        return ApiEnvelope.ok(new InterviewDtos.StartResponse(OPENER, focusPoints));
    }

    /** 4.3 面试对话：SSE 流式推送。 */
    @PostMapping(value = "/{sessionId}/chat", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter chat(
            @RequestAttribute(AuthInterceptor.USER_ID_ATTRIBUTE) Long userId,
            @PathVariable String sessionId,
            @RequestBody InterviewDtos.ChatRequest body) {
        String content = body.content();
        if (content == null || content.isBlank()) {
            throw ApiException.badRequest("消息内容不能为空");
        }
        if (content.length() > MAX_CONTENT_CHARS) {
            throw ApiException.tooLarge("单条消息超过 8KB 上限");
        }
        SessionState state = owned(userId, sessionId);
        if (!SessionState.IN_PROGRESS.equals(state.status())) {
            throw ApiException.conflict("面试未开始或已结束，无法继续对话");
        }
        return chatStream.chat(sessionId, content);
    }

    /** 4.4 结束面试：幂等，已有任务直接回状态，失败任务自动重提。 */
    @PostMapping("/{sessionId}/end")
    public ApiEnvelope<InterviewDtos.ReportStatusResponse> end(
            @RequestAttribute(AuthInterceptor.USER_ID_ATTRIBUTE) Long userId,
            @PathVariable String sessionId) {
        SessionState state = owned(userId, sessionId);
        if (SessionState.CREATED.equals(state.status())) {
            throw ApiException.conflict("面试尚未开始");
        }
        return ApiEnvelope.ok(new InterviewDtos.ReportStatusResponse(ensureReportTask(sessionId)));
    }

    /** 4.5 复盘报告：生成中回信封 code 202，完成回完整报告对象。 */
    @GetMapping("/{sessionId}/report")
    public ResponseEntity<ApiEnvelope<Object>> report(
            @RequestAttribute(AuthInterceptor.USER_ID_ATTRIBUTE) Long userId,
            @PathVariable String sessionId) {
        owned(userId, sessionId);
        ReportTask task = reportStore.loadBySession(sessionId)
                .orElseThrow(() -> ApiException.notFound("该会话尚未生成复盘报告"));
        return switch (task.status()) {
            case ReportTask.GENERATING, ReportTask.RUNNING -> generating(task.status());
            case ReportTask.FAILED -> generating(reportStore.submit(sessionId).status());
            case ReportTask.DONE -> ResponseEntity.ok(ApiEnvelope.ok(
                    gson.fromJson(task.reportJson(), InterviewReport.class)));
            default -> throw new ApiException(500, "报告任务状态异常：" + task.status());
        };
    }

    /** 4.6 历史会话列表：按当前用户分页倒序。 */
    @GetMapping
    public ApiEnvelope<InterviewDtos.ListResponse> list(
            @RequestAttribute(AuthInterceptor.USER_ID_ATTRIBUTE) Long userId,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int size) {
        int safePage = Math.max(1, page);
        int safeSize = Math.min(Math.max(1, size), MAX_PAGE_SIZE);
        List<SessionOverview> list = store.listOverviews(userId, safePage, safeSize);
        return ApiEnvelope.ok(new InterviewDtos.ListResponse(store.countOverviews(userId), list));
    }

    /** 4.7 会话详情：刷新恢复与消息记录的数据源，角色转大写并回带题型与时间。 */
    @GetMapping("/{sessionId}")
    public ApiEnvelope<InterviewDtos.DetailResponse> detail(
            @RequestAttribute(AuthInterceptor.USER_ID_ATTRIBUTE) Long userId,
            @PathVariable String sessionId) {
        SessionState state = owned(userId, sessionId);
        List<InterviewDtos.MessageView> messages = store.readAll(sessionId).stream()
                .map(InterviewController::toView).toList();
        return ApiEnvelope.ok(new InterviewDtos.DetailResponse(state.overview(), messages));
    }

    private InterviewDtos.CreateResponse create(Long userId, String resumeText,
                                                String jdText, SessionConfig config) {
        if (jdText == null || jdText.isBlank()) {
            throw ApiException.badRequest("JD 文本不能为空");
        }
        if (jdText.length() > MAX_JD_CHARS) {
            throw ApiException.tooLarge("JD 文本超过 20KB 上限");
        }
        Supervisor.CreateResult created = supervisor.createSession(userId, resumeText,
                jdText, validConfig(config));
        return new InterviewDtos.CreateResponse(created.sessionId(), created.resumeDigest());
    }

    /** 结束并保证报告任务：题数不足只结束不建任务（报告对空对话无意义）。 */
    private String ensureReportTask(String sessionId) {
        ReportTask existing = reportStore.loadBySession(sessionId).orElse(null);
        if (existing != null) {
            if (ReportTask.FAILED.equals(existing.status())) {
                return reportStore.submit(sessionId).status();
            }
            return existing.status();
        }
        store.endSession(sessionId);
        List<QaPair> pairs = ReportGenerator.pairUp(store.readAll(sessionId));
        if (pairs.size() < MIN_PAIRS) {
            return "SKIPPED";
        }
        return reportStore.submit(sessionId).status();
    }

    private static ResponseEntity<ApiEnvelope<Object>> generating(String status) {
        return ResponseEntity.ok(new ApiEnvelope<>(202, "复盘报告生成中",
                new InterviewDtos.ReportStatusResponse(status)));
    }

    /** 归属校验：他人会话与不存在一律 404，不泄露存在性。 */
    private SessionState owned(Long userId, String sessionId) {
        SessionState state = store.loadSession(sessionId)
                .orElseThrow(() -> ApiException.notFound("会话不存在"));
        if (state.userId() == null || !state.userId().equals(userId)) {
            throw ApiException.notFound("会话不存在");
        }
        return state;
    }

    private static InterviewDtos.MessageView toView(StoredMessage message) {
        String role = "user".equals(message.role()) ? "CANDIDATE" : "INTERVIEWER";
        return new InterviewDtos.MessageView(String.valueOf(message.seq()), role,
                message.text(), message.inputTokens() + message.outputTokens(),
                message.createdAt(), message.action());
    }

    private String requireResumeText(String resumeText) {
        if (resumeText == null || resumeText.isBlank()) {
            throw ApiException.badRequest("简历文本不能为空");
        }
        if (resumeText.length() > MAX_RESUME_TEXT_CHARS) {
            throw ApiException.tooLarge("简历文本超过 200KB 上限");
        }
        return resumeText;
    }

    private SessionConfig parseConfig(String configJson) {
        try {
            return gson.fromJson(configJson, SessionConfig.class);
        } catch (RuntimeException e) {
            throw ApiException.badRequest("config 参数不是合法 JSON");
        }
    }

    private static SessionConfig validConfig(SessionConfig config) {
        if (config == null) {
            return new SessionConfig("JAVA_BACKEND", "MEDIUM", 10);
        }
        if (config.direction() == null || config.direction().isBlank()) {
            throw ApiException.badRequest("direction 不能为空");
        }
        if (config.difficulty() == null || !DIFFICULTIES.contains(config.difficulty())) {
            throw ApiException.badRequest("difficulty 需为 EASY、MEDIUM 或 HARD");
        }
        if (config.questionCount() < 1 || config.questionCount() > 30) {
            throw ApiException.badRequest("questionCount 需在 1 到 30 之间");
        }
        return config;
    }
}
