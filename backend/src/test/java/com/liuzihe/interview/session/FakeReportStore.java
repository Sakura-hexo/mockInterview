package com.liuzihe.interview.session;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * 报告单测用内存实现：LinkedHashMap 保插入顺序即 claim 的 FIFO 语义。
 */
public final class FakeReportStore implements ReportStore {

    public final Map<String, ReportTask> tasks = new LinkedHashMap<>();

    @Override
    public ReportTask submit(String sessionId) {
        ReportTask existing = tasks.get(sessionId);
        if (existing == null) {
            ReportTask created = new ReportTask(
                    "r-" + UUID.randomUUID().toString().substring(0, 8), sessionId,
                    ReportTask.GENERATING, 0, null, null);
            tasks.put(sessionId, created);
            return created;
        }
        if (ReportTask.FAILED.equals(existing.status())) {
            ReportTask reset = new ReportTask(existing.taskId(), sessionId,
                    ReportTask.GENERATING, 0, null, null);
            tasks.put(sessionId, reset);
            return reset;
        }
        return existing;
    }

    @Override
    public Optional<ReportTask> claim() {
        for (Map.Entry<String, ReportTask> entry : tasks.entrySet()) {
            if (ReportTask.GENERATING.equals(entry.getValue().status())) {
                ReportTask claimed = copy(entry.getValue(), ReportTask.RUNNING,
                        entry.getValue().retryCount(), null, null);
                tasks.put(entry.getKey(), claimed);
                return Optional.of(claimed);
            }
        }
        return Optional.empty();
    }

    @Override
    public void complete(String taskId, String reportJson) {
        tasks.entrySet().stream()
                .filter(entry -> entry.getValue().taskId().equals(taskId))
                .findFirst()
                .ifPresent(entry -> tasks.put(entry.getKey(),
                        copy(entry.getValue(), ReportTask.DONE,
                                entry.getValue().retryCount(), reportJson, null)));
    }

    @Override
    public void fail(String taskId, String errorMsg) {
        tasks.entrySet().stream()
                .filter(entry -> entry.getValue().taskId().equals(taskId))
                .findFirst()
                .ifPresent(entry -> {
                    ReportTask current = entry.getValue();
                    int retry = current.retryCount() + 1;
                    String status = retry >= 3 ? ReportTask.FAILED : ReportTask.GENERATING;
                    tasks.put(entry.getKey(), copy(current, status, retry, null, errorMsg));
                });
    }

    @Override
    public Optional<ReportTask> loadBySession(String sessionId) {
        return Optional.ofNullable(tasks.get(sessionId));
    }

    @Override
    public int resetStaleRunning() {
        int reset = 0;
        for (Map.Entry<String, ReportTask> entry : tasks.entrySet()) {
            if (ReportTask.RUNNING.equals(entry.getValue().status())) {
                tasks.put(entry.getKey(), copy(entry.getValue(), ReportTask.GENERATING,
                        entry.getValue().retryCount(), null, null));
                reset++;
            }
        }
        return reset;
    }

    private static ReportTask copy(ReportTask task, String status, int retryCount,
                                   String reportJson, String errorMsg) {
        return new ReportTask(task.taskId(), task.sessionId(), status,
                retryCount, reportJson, errorMsg);
    }
}
