package com.sakura.interview.api;

import com.sakura.interview.infra.EnvKeys;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestAttribute;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.nio.charset.StandardCharsets;

/**
 * 用量统计与管理端：token 按消息口径聚合（口径与边界见 MySqlStatsStore 注释），
 * 管理功能（全站总览、题库上传）经 AdminGate 门禁，非管理员一律 404 零暴露。
 */
@RestController
@RequestMapping("/api/stats")
public final class StatsController {

    private static final int TREND_DAYS = 30;
    private static final int SESSION_LIMIT = 20;
    private static final int USER_LIMIT = 20;
    private static final int MAX_BANK_BYTES = 1024 * 1024;
    private static final int MAX_BANK_QUESTIONS = 500;

    private final StatsStore statsStore;
    private final UserStore userStore;
    private final QuestionBankImportPort bankImporter;
    private final AdminGate adminGate;

    public StatsController(StatsStore statsStore, UserStore userStore,
                           QuestionBankImportPort bankImporter, AdminGate adminGate) {
        this.statsStore = statsStore;
        this.userStore = userStore;
        this.bankImporter = bankImporter;
        this.adminGate = adminGate;
    }

    /** 我的用量：汇总卡 + 近 30 天趋势 + 最近会话明细（登录态即可）。 */
    @GetMapping("/tokens")
    public ApiEnvelope<StatsDtos.TokenStatsResponse> myTokens(
            @RequestAttribute(AuthInterceptor.USER_ID_ATTRIBUTE) Long userId) {
        return ApiEnvelope.ok(new StatsDtos.TokenStatsResponse(
                StatsDtos.SummaryView.from(statsStore.userSummary(userId)),
                statsStore.dailyTrend(userId, TREND_DAYS),
                statsStore.recentSessions(userId, SESSION_LIMIT)));
    }

    /** 管理员总览：全站汇总 + 按用户排行。 */
    @GetMapping("/admin/overview")
    public ApiEnvelope<StatsDtos.AdminOverviewResponse> adminOverview(
            @RequestAttribute(AuthInterceptor.USER_ID_ATTRIBUTE) Long userId) {
        requireAdmin(userId);
        return ApiEnvelope.ok(new StatsDtos.AdminOverviewResponse(
                StatsDtos.SummaryView.from(statsStore.siteSummary()),
                statsStore.topUsers(USER_LIMIT)));
    }

    /** 题库上传：multipart Markdown，幂等导入（题号已存在跳过），同步返回结果。 */
    @PostMapping(value = "/admin/question-bank", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ApiEnvelope<StatsDtos.BankUploadResponse> uploadQuestionBank(
            @RequestAttribute(AuthInterceptor.USER_ID_ATTRIBUTE) Long userId,
            @RequestParam("file") MultipartFile file) throws IOException {
        requireAdmin(userId);
        if (file == null || file.isEmpty()) {
            throw ApiException.badRequest("题库文件不能为空");
        }
        byte[] bytes = file.getBytes();
        if (bytes.length > MAX_BANK_BYTES) {
            throw ApiException.tooLarge("题库文件超过 1MB 上限");
        }
        QuestionBankImportPort.ImportResult result;
        try {
            result = bankImporter.importText(new String(bytes, StandardCharsets.UTF_8));
        } catch (IllegalArgumentException e) {
            throw ApiException.badRequest(e.getMessage());
        }
        if (result.parsed() > MAX_BANK_QUESTIONS) {
            throw ApiException.tooLarge("单次上传最多 " + MAX_BANK_QUESTIONS + " 题");
        }
        return ApiEnvelope.ok(new StatsDtos.BankUploadResponse(
                result.parsed(), result.imported(), result.skipped()));
    }

    /** 门禁：未配置管理员或当前用户不是管理员，一律按接口不存在处理（404 零暴露）。 */
    private void requireAdmin(Long userId) {
        String username = userStore.findById(userId)
                .orElseThrow(() -> ApiException.unauthorized("登录态无效"))
                .username();
        if (!adminGate.isOwner(username)) {
            throw ApiException.notFound("接口不存在");
        }
    }

    /** 装配层用：从 env 构造门禁。 */
    public static AdminGate gateFromEnv() {
        return AdminGate.forUsername(EnvKeys.adminUsername());
    }
}
