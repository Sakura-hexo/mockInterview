package com.liuzihe.interview.session;

/**
 * 报告任务：状态机 GENERATING、RUNNING、DONE、FAILED；retryCount 为已失败次数，
 * 达 3 次置 FAILED；reportJson 在 DONE 后为 InterviewReport 序列化结果。
 */
public record ReportTask(String taskId, String sessionId, String status,
                         int retryCount, String reportJson, String errorMsg) {

    public static final String GENERATING = "GENERATING";
    public static final String RUNNING = "RUNNING";
    public static final String DONE = "DONE";
    public static final String FAILED = "FAILED";
}
