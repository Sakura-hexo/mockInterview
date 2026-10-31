package com.sakura.interview.session;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * 会话单测用内存实现：语义与生产实现对齐——seq 会话内单调递增、
 * 窗口取最近 N 条升序、批次取 seq 大于指定值的最早若干条升序。
 */
public final class FakeSessionStore implements SessionStore {

    public final Map<String, SessionState> sessions = new HashMap<>();
    public final Map<String, List<StoredMessage>> messages = new HashMap<>();
    public final Map<String, SessionSummary> summaries = new HashMap<>();

    @Override
    public void saveSession(SessionState state) {
        sessions.put(state.sessionId(), state);
    }

    @Override
    public Optional<SessionState> loadSession(String sessionId) {
        return Optional.ofNullable(sessions.get(sessionId));
    }

    @Override
    public int appendMessage(String sessionId, String role, String text,
                             long inputTokens, long outputTokens) {
        List<StoredMessage> list = messages.computeIfAbsent(sessionId, key -> new ArrayList<>());
        int seq = list.size() + 1;
        list.add(new StoredMessage(seq, role, text, inputTokens, outputTokens));
        return seq;
    }

    @Override
    public List<StoredMessage> readWindow(String sessionId, int limit) {
        List<StoredMessage> list = messages.getOrDefault(sessionId, List.of());
        if (list.size() <= limit) {
            return List.copyOf(list);
        }
        return List.copyOf(list.subList(list.size() - limit, list.size()));
    }

    @Override
    public List<StoredMessage> readBatch(String sessionId, int afterSeq, int limit) {
        return messages.getOrDefault(sessionId, List.of()).stream()
                .filter(message -> message.seq() > afterSeq)
                .limit(limit)
                .toList();
    }

    @Override
    public Optional<SessionSummary> loadSummary(String sessionId) {
        return Optional.ofNullable(summaries.get(sessionId));
    }

    @Override
    public void saveSummary(String sessionId, String text, int coveredSeq) {
        summaries.put(sessionId, new SessionSummary(text, coveredSeq));
    }

    @Override
    public void deleteSession(String sessionId) {
        sessions.remove(sessionId);
        messages.remove(sessionId);
        summaries.remove(sessionId);
    }
}
