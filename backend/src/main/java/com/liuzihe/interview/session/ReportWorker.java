package com.liuzihe.interview.session;

import com.google.gson.Gson;

import java.time.Duration;

/**
 * 报告 Worker：虚拟线程单并发，固定间隔轮询任务表领任务执行。
 * 启动时先把残留 RUNNING 重置回 GENERATING（进程重启恢复）；
 * 单任务异常经 fail 记账不外泄，绝不中断轮询。
 */
public final class ReportWorker implements AutoCloseable {

    private static final Duration DEFAULT_POLL_INTERVAL = Duration.ofSeconds(2);
    private static final Duration STOP_TIMEOUT = Duration.ofSeconds(3);

    private final ReportStore reportStore;
    private final ReportGenerator generator;
    private final Duration pollInterval;
    private final Gson gson = new Gson();
    private final Thread thread;
    private volatile boolean running = true;

    public ReportWorker(ReportStore reportStore, ReportGenerator generator) {
        this(reportStore, generator, DEFAULT_POLL_INTERVAL);
    }

    /** 测试用：可注入更短的轮询间隔。 */
    ReportWorker(ReportStore reportStore, ReportGenerator generator, Duration pollInterval) {
        this.reportStore = reportStore;
        this.generator = generator;
        this.pollInterval = pollInterval;
        this.thread = Thread.ofVirtual().name("report-worker").unstarted(this::loop);
    }

    public ReportWorker start() {
        int recovered = reportStore.resetStaleRunning();
        if (recovered > 0) {
            System.err.printf("[报告Worker] 启动恢复：%d 个残留 RUNNING 任务已重置%n", recovered);
        }
        thread.start();
        return this;
    }

    private void loop() {
        while (running) {
            try {
                reportStore.claim().ifPresent(this::process);
            } catch (RuntimeException e) {
                System.err.printf("[报告Worker] 轮询异常：%s%n", e.getMessage());
            }
            try {
                Thread.sleep(pollInterval.toMillis());
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
        }
    }

    private void process(ReportTask task) {
        try {
            InterviewReport report = generator.generate(task.sessionId());
            reportStore.complete(task.taskId(), gson.toJson(report));
        } catch (RuntimeException e) {
            System.err.printf("[报告Worker] 任务 %s 失败：%s%n", task.taskId(), e.getMessage());
            reportStore.fail(task.taskId(), e.getMessage());
        }
    }

    @Override
    public void close() {
        running = false;
        thread.interrupt();
        try {
            thread.join(STOP_TIMEOUT);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
