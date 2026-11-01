package com.sakura.interview.session;

import com.google.gson.Gson;
import org.springframework.transaction.support.TransactionOperations;

import java.time.Duration;

/**
 * 报告 Worker：虚拟线程单并发，固定间隔轮询任务表领任务执行。
 * 启动时先把残留 RUNNING 重置回 GENERATING（进程重启恢复）；
 * 单任务异常经 fail 记账不外泄，绝不中断轮询。
 * complete 与会话推进 REPORTED 两写可注入 TransactionOperations 同事务提交。
 */
public final class ReportWorker implements AutoCloseable {

    private static final Duration DEFAULT_POLL_INTERVAL = Duration.ofSeconds(2);
    private static final Duration STOP_TIMEOUT = Duration.ofSeconds(3);

    private final ReportStore reportStore;
    private final ReportGenerator generator;
    private final SessionStore sessionStore;
    private final TransactionOperations txOps;
    private final Duration pollInterval;
    private final Gson gson = new Gson();
    private final Thread thread;
    private volatile boolean running = true;

    public ReportWorker(ReportStore reportStore, ReportGenerator generator) {
        this(reportStore, generator, null, null, DEFAULT_POLL_INTERVAL);
    }

    /** Web 装配用：完成任务时把会话推进到 REPORTED；sessionStore 为空则跳过（CLI 口径）。 */
    public ReportWorker(ReportStore reportStore, ReportGenerator generator,
                        SessionStore sessionStore) {
        this(reportStore, generator, sessionStore, null, DEFAULT_POLL_INTERVAL);
    }

    /** Web 装配用：两写同事务提交（注入 TransactionTemplate）。 */
    public ReportWorker(ReportStore reportStore, ReportGenerator generator,
                        SessionStore sessionStore, TransactionOperations txOps) {
        this(reportStore, generator, sessionStore, txOps, DEFAULT_POLL_INTERVAL);
    }

    /** 测试用：可注入更短的轮询间隔。 */
    ReportWorker(ReportStore reportStore, ReportGenerator generator, Duration pollInterval) {
        this(reportStore, generator, null, null, pollInterval);
    }

    private ReportWorker(ReportStore reportStore, ReportGenerator generator,
                        SessionStore sessionStore, TransactionOperations txOps, Duration pollInterval) {
        this.reportStore = reportStore;
        this.generator = generator;
        this.sessionStore = sessionStore;
        this.txOps = txOps;
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
            // 两写同事务：中途崩溃不留"报告完成但会话停在 ENDED"的漂移
            Runnable finish = () -> {
                reportStore.complete(task.taskId(), gson.toJson(report));
                if (sessionStore != null) {
                    sessionStore.updateStatus(task.sessionId(), SessionState.REPORTED);
                }
            };
            if (txOps == null) {
                finish.run();
            } else {
                txOps.executeWithoutResult(ignored -> finish.run());
            }
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
