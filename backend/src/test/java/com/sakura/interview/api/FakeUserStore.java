package com.sakura.interview.api;

import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 认证单测用的内存用户表：语义与 MySQL 实现对齐，用户名唯一冲突抛 IllegalStateException。
 */
public final class FakeUserStore implements UserStore {

    public final Map<String, UserRecord> byUsername = new HashMap<>();
    private final AtomicLong ids = new AtomicLong();

    @Override
    public long create(String username, String passwordHash) {
        if (byUsername.containsKey(username)) {
            throw new IllegalStateException("用户名已被占用：" + username);
        }
        long id = ids.incrementAndGet();
        byUsername.put(username, new UserRecord(id, username, passwordHash));
        return id;
    }

    @Override
    public Optional<UserRecord> findByUsername(String username) {
        return Optional.ofNullable(byUsername.get(username));
    }

    @Override
    public Optional<UserRecord> findById(long id) {
        return byUsername.values().stream().filter(u -> u.id() == id).findFirst();
    }

    @Override
    public void updatePasswordHash(long id, String passwordHash) {
        byUsername.values().stream()
                .filter(u -> u.id() == id)
                .findFirst()
                .ifPresent(u -> byUsername.put(u.username(),
                        new UserRecord(id, u.username(), passwordHash)));
    }
}
