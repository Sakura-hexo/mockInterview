package com.sakura.interview.session;

import java.util.Optional;

/**
 * 报告任务存储端口：MySQL 任务表语义，单测用内存实现。
 * 一场会话一份报告：submit 幂等，DONE 后复用，FAILED 后重置重试。
 */
public interface ReportStore {

    /** 提交或复用任务，返回提交后的任务状态。 */
    ReportTask submit(String sessionId);

    /** 领取最早的 GENERATING 任务并置 RUNNING，无任务返回空。 */
    Optional<ReportTask> claim();

    /** 写入报告并置 DONE。 */
    void complete(String taskId, String reportJson);

    /** 记一次失败：重试次数加一，未达 3 次置回 GENERATING，达到则置 FAILED 并记原因。 */
    void fail(String taskId, String errorMsg);

    Optional<ReportTask> loadBySession(String sessionId);

    /** 启动恢复：把残留 RUNNING 重置回 GENERATING，返回重置数量。 */
    int resetStaleRunning();
}
