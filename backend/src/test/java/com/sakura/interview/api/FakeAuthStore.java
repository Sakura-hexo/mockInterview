package com.sakura.interview.api;

import java.util.HashMap;
import java.util.Map;

/**
 * 认证单测用的内存会话表：语义与 Redis 实现对齐——
 * 会话可删除即失效、失败计数达阈值进入锁定。
 */
public final class FakeAuthStore implements AuthStore {

    public final Map<String, Long> sessions = new HashMap<>();
    public final Map<String, Integer> loginFailures = new HashMap<>();
    public int renewCount = 0;

    @Override
    public void saveSession(String token, long userId) {
        sessions.put(token, userId);
    }

    @Override
    public Long resolveUserId(String token) {
        if (sessions.containsKey(token)) {
            renewCount++;
        }
        return sessions.get(token);
    }

    @Override
    public void deleteSession(String token) {
        sessions.remove(token);
    }

    @Override
    public boolean isLoginLocked(String username) {
        return loginFailures.getOrDefault(username, 0) >= 5;
    }

    @Override
    public void recordLoginFailure(String username) {
        loginFailures.merge(username, 1, Integer::sum);
    }

    @Override
    public void clearLoginFailures(String username) {
        loginFailures.remove(username);
    }
}
