package com.sakura.interview.session;

import java.util.List;
import java.util.Optional;

/**
 * 会话存储端口：编排层只依赖此接口，单测用内存实现，
 * 生产实现为 MySQL 事实源加 Redis 热存的组合（见 infra）。
 */
public interface SessionStore {

    /** 会话元数据写入或覆盖（按 sessionId 幂等，不改写 status 与归属）。 */
    void saveSession(SessionState state);

    Optional<SessionState> loadSession(String sessionId);

    /** 推进状态机：CREATED、IN_PROGRESS、ENDED、REPORTED。 */
    void updateStatus(String sessionId, String status);

    /** 追加一条消息（无题型标注），返回分配到的会话内序号。 */
    default int appendMessage(String sessionId, String role, String text,
                               long inputTokens, long outputTokens) {
        return appendMessage(sessionId, role, text, inputTokens, outputTokens, null);
    }

    /** 追加一条带题型标注的消息（QUESTION、FOLLOW_UP、NEXT，仅面试官消息使用）。 */
    int appendMessage(String sessionId, String role, String text,
                      long inputTokens, long outputTokens, String action);

    /** 最近 limit 条消息，按 seq 升序，含当前轮。 */
    List<StoredMessage> readWindow(String sessionId, int limit);

    /** seq 大于 afterSeq 的最早 limit 条消息，升序，供压缩器取批。 */
    List<StoredMessage> readBatch(String sessionId, int afterSeq, int limit);

    /** 全量消息升序，供报告评估与会话详情整体读取（不受窗口限制）。 */
    List<StoredMessage> readAll(String sessionId);

    /** 按 userId 分页的历史会话概览，倒序；userId 为 null 时返回全部（CLI 自检用）。 */
    List<SessionOverview> listOverviews(Long userId, int page, int size);

    /** 历史会话总数（与 listOverviews 同一过滤口径）。 */
    long countOverviews(Long userId);

    Optional<SessionSummary> loadSummary(String sessionId);

    void saveSummary(String sessionId, String text, int coveredSeq);

    /** 删除会话全部数据（元数据、消息、摘要），供自检清理。 */
    void deleteSession(String sessionId);

    /** 结束会话：置 ENDED 与结束时间（报告提交时调用）。 */
    void endSession(String sessionId);

    /** 首末消息时间差（毫秒），无消息返回 0（报告时长统计用）。 */
    long durationMs(String sessionId);
}
