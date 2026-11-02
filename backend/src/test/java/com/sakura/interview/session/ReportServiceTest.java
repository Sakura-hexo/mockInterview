package com.sakura.interview.session;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class ReportServiceTest {

    private static FakeSessionStore sessionStoreWithPairs(int pairCount) {
        FakeSessionStore store = new FakeSessionStore();
        store.sessions.put("s", new SessionState("s", "三年 Java 后端",
                new com.sakura.interview.tool.JdProfile("Java后端", List.of("Spring"), List.of())));
        for (int i = 1; i <= pairCount; i++) {
            store.appendMessage("s", "interviewer", "第" + i + "题", 1, 1);
            store.appendMessage("s", "user", "第" + i + "题回答", 0, 0);
        }
        return store;
    }

    @Test
    void 题数不足提示继续不建任务() {
        FakeSessionStore sessions = sessionStoreWithPairs(1);
        FakeReportStore reports = new FakeReportStore();
        ReportService service = new ReportService(sessions, reports);

        String reply = service.handle("s");

        assertThat(reply).contains("样本太少").contains("继续作答");
        assertThat(reports.tasks).isEmpty();
        assertThat(sessions.endedSessions).isEmpty();
    }

    @Test
    void 题数足够结束会话并提交任务() {
        FakeSessionStore sessions = sessionStoreWithPairs(3);
        FakeReportStore reports = new FakeReportStore();
        ReportService service = new ReportService(sessions, reports);

        String reply = service.handle("s");

        assertThat(reply).contains("生成中").contains("看报告");
        ReportTask task = reports.tasks.get("s");
        assertThat(task).isNotNull();
        assertThat(task.status()).isEqualTo(ReportTask.GENERATING);
        assertThat(sessions.endedSessions).containsExactly("s");
    }

    @Test
    void 已有生成中任务提示稍候() {
        FakeSessionStore sessions = sessionStoreWithPairs(2);
        FakeReportStore reports = new FakeReportStore();
        reports.submit("s");
        ReportService service = new ReportService(sessions, reports);

        String reply = service.handle("s");

        assertThat(reply).contains("仍在生成中");
    }

    @Test
    void 已完成任务输出完整Markdown报告() {
        FakeSessionStore sessions = sessionStoreWithPairs(2);
        FakeReportStore reports = new FakeReportStore();
        reports.submit("s");
        reports.complete(reports.tasks.get("s").taskId(), sampleReportJson());
        ReportService service = new ReportService(sessions, reports);

        String reply = service.handle("s");

        assertThat(reply)
                .contains("# 面试复盘报告")
                .contains("技术深度")
                .contains("逐题回顾")
                .contains("HashMap");
    }

    @Test
    void 失败任务重新提交重试() {
        FakeSessionStore sessions = sessionStoreWithPairs(2);
        FakeReportStore reports = new FakeReportStore();
        reports.submit("s");
        reports.fail(reports.tasks.get("s").taskId(), "模型超时");
        reports.fail(reports.tasks.get("s").taskId(), "模型超时");
        reports.fail(reports.tasks.get("s").taskId(), "模型超时");
        assertThat(reports.tasks.get("s").status()).isEqualTo(ReportTask.FAILED);
        ReportService service = new ReportService(sessions, reports);

        String reply = service.handle("s");

        assertThat(reply).contains("重新提交");
        assertThat(reports.tasks.get("s").status()).isEqualTo(ReportTask.GENERATING);
        assertThat(reports.tasks.get("s").retryCount()).isZero();
    }

    @Test
    void 会话不存在抛异常() {
        ReportService service = new ReportService(new FakeSessionStore(), new FakeReportStore());
        assertThat(switchError(() -> service.handle("s-none")))
                .contains("会话不存在");
    }

    private static String switchError(Runnable runnable) {
        try {
            runnable.run();
        } catch (RuntimeException e) {
            return e.getMessage();
        }
        throw new AssertionError("预期抛出异常");
    }

    private static String sampleReportJson() {
        InterviewReport report = new InterviewReport("s",
                new ReportScore(7, 7, 7, 7),
                new ArrayList<>(List.of(new ReportItem("HashMap 底层结构", "数组链表红黑树", "扎实", 7))),
                List.of("建议一"), 1000, 60000);
        return new com.google.gson.Gson().toJson(report);
    }
}
