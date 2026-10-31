package com.sakura.interview.session;

import java.util.List;
import java.util.Optional;

/**
 * 会话存储端口：编排层只依赖此接口，单测用内存实现，
 * 生产实现为 MySQL 事实源加 Redis 热存的组合（见 infra）。
 */
public interface SessionStore {

    /** 会话元数据写入或覆盖（按 sessionId 幂等）。 */
    void saveSession(SessionState state);

    Optional<SessionState> loadSession(String sessionId);

    /** 追加一条消息，返回分配到的会话内序号。 */
    int appendMessage(String sessionId, String role, String text,
                      long inputTokens, long outputTokens);

    /** 最近 limit 条消息，按 seq 升序，含当前轮。 */
    List<StoredMessage> readWindow(String sessionId, int limit);

    /** seq 大于 afterSeq 的最早 limit 条消息，升序，供压缩器取批。 */
    List<StoredMessage> readBatch(String sessionId, int afterSeq, int limit);

    Optional<SessionSummary> loadSummary(String sessionId);

    void saveSummary(String sessionId, String text, int coveredSeq);

    /** 删除会话全部数据（元数据、消息、摘要），供自检清理。 */
    void deleteSession(String sessionId);
}
