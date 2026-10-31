package com.liuzihe.interview.session;

import com.google.gson.Gson;
import com.liuzihe.interview.infra.StructuredOutputInvoker;
import com.liuzihe.interview.tool.JdProfile;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.fail;

class ReportWorkerTest {

    private static final Duration FAST_POLL = Duration.ofMillis(10);

    private static ReportGenerator okGenerator(FakeSessionStore store) {
        return new ReportGenerator(new StructuredOutputInvoker((sys, user) -> {
            if (sys.contains("逐题评估器")) {
                return "{\"items\":[{\"question\":\"题\",\"userAnswerSummary\":\"答\","
                        + "\"comment\":\"好\",\"score\":7}]}";
            }
            return "{\"scores\":{\"overall\":7,\"technicalDepth\":7,\"expression\":7,"
                    + "\"followUpHandling\":7},\"suggestions\":[\"建议\"]}";
        }), store);
    }

    private static FakeSessionStore sessionWithPairs(int pairCount) {
        FakeSessionStore store = new FakeSessionStore();
        store.sessions.put("s", new SessionState("s", "三年 Java 后端",
                new JdProfile("Java后端", List.of("Spring"), List.of())));
        for (int i = 1; i <= pairCount; i++) {
            store.appendMessage("s", "interviewer", "第" + i + "题", 1, 1);
            store.appendMessage("s", "user", "第" + i + "题回答", 0, 0);
        }
        return store;
    }

    @Test
    void 领取任务生成报告置DONE() {
        FakeSessionStore sessions = sessionWithPairs(3);
        FakeReportStore reports = new FakeReportStore();
        reports.submit("s");
        try (ReportWorker worker = new ReportWorker(reports, okGenerator(sessions), FAST_POLL).start()) {
            awaitTrue(() -> ReportTask.DONE.equals(reports.tasks.get("s").status()), "任务完成");
            InterviewReport report =
                    new Gson().fromJson(reports.tasks.get("s").reportJson(), InterviewReport.class);
            assertThat(report.items()).isNotEmpty();
            assertThat(report.scores().overall()).isEqualTo(7);
            assertThat(reports.tasks.get("s").errorMsg()).isNull();
        }
    }

    @Test
    void 连续失败三次置FAILED并记原因() {
        FakeSessionStore sessions = new FakeSessionStore();
        FakeReportStore reports = new FakeReportStore();
        reports.submit("s-missing");
        try (ReportWorker worker = new ReportWorker(reports, okGenerator(sessions), FAST_POLL).start()) {
            awaitTrue(() -> ReportTask.FAILED.equals(reports.tasks.get("s-missing").status()),
                    "任务失败终止");
            ReportTask task = reports.tasks.get("s-missing");
            assertThat(task.retryCount()).isEqualTo(3);
            assertThat(task.errorMsg()).contains("会话不存在");
        }
    }

    @Test
    void 启动恢复残留RUNNING任务() {
        FakeSessionStore sessions = sessionWithPairs(2);
        FakeReportStore reports = new FakeReportStore();
        reports.submit("s");
        reports.claim();
        assertThat(reports.tasks.get("s").status()).isEqualTo(ReportTask.RUNNING);

        try (ReportWorker worker = new ReportWorker(reports, okGenerator(sessions), FAST_POLL).start()) {
            awaitTrue(() -> ReportTask.DONE.equals(reports.tasks.get("s").status()), "恢复后完成");
        }
    }

    private static void awaitTrue(Supplier<Boolean> condition, String description) {
        long deadline = System.currentTimeMillis() + 5000;
        while (System.currentTimeMillis() < deadline) {
            if (Boolean.TRUE.equals(condition.get())) {
                return;
            }
            try {
                Thread.sleep(20);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
        }
        fail("等待超时：" + description);
    }
}
