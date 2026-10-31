package com.liuzihe.interview.session;

import com.google.gson.Gson;
import com.liuzihe.interview.tool.ReportExportTool;

import java.util.List;
import java.util.Optional;

/**
 * 报告服务：REPORT 意图的对外门面，五分支——
 * 题数不足提示继续；无任务则结束会话并提交；生成中提示稍候；
 * 已完成输出完整 Markdown；失败则重置重试。
 */
public final class ReportService {

    private static final int MIN_PAIRS = 2;

    private final SessionStore sessionStore;
    private final ReportStore reportStore;
    private final Gson gson = new Gson();

    public ReportService(SessionStore sessionStore, ReportStore reportStore) {
        this.sessionStore = sessionStore;
        this.reportStore = reportStore;
    }

    public String handle(String sessionId) {
        sessionStore.loadSession(sessionId)
                .orElseThrow(() -> new IllegalStateException("会话不存在：" + sessionId));
        Optional<ReportTask> existing = reportStore.loadBySession(sessionId);
        if (existing.isEmpty()) {
            List<QaPair> pairs = ReportGenerator.pairUp(sessionStore.readAll(sessionId));
            if (pairs.size() < MIN_PAIRS) {
                return "当前对话只覆盖 " + pairs.size()
                        + " 道题，样本太少无法生成有意义的复盘，请继续作答几轮后再结束面试。";
            }
            sessionStore.endSession(sessionId);
            ReportTask task = reportStore.submit(sessionId);
            return "面试已结束，复盘报告生成中（任务 " + task.taskId()
                    + "），预计两到四分钟。稍后输入「看报告」获取结果。";
        }
        ReportTask task = existing.get();
        return switch (task.status()) {
            case ReportTask.GENERATING, ReportTask.RUNNING ->
                    "复盘报告仍在生成中，请稍后再输入「看报告」。";
            case ReportTask.DONE -> exportReport(task);
            case ReportTask.FAILED -> {
                ReportTask retried = reportStore.submit(sessionId);
                yield "上一次生成失败（" + task.errorMsg()
                        + "），已重新提交生成（任务 " + retried.taskId() + "）。";
            }
            default -> "报告任务状态异常：" + task.status();
        };
    }

    private String exportReport(ReportTask task) {
        try {
            InterviewReport report = gson.fromJson(task.reportJson(), InterviewReport.class);
            return new ReportExportTool().export(report);
        } catch (RuntimeException e) {
            return "报告已生成但解析输出失败（" + e.getMessage()
                    + "），可运行 report show " + task.sessionId() + " 查看。";
        }
    }
}
