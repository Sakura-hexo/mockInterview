package com.sakura.interview.infra;

import com.sakura.interview.api.AuthStore;
import io.lettuce.core.RedisClient;
import io.lettuce.core.SetArgs;
import io.lettuce.core.api.StatefulRedisConnection;
import io.lettuce.core.api.sync.RedisCommands;

import java.time.Duration;

/**
 * AuthStore 的 Redis 实现：
 * 会话键 auth:session:token 值 userId，TTL 24 小时，解析命中即续期（滑动过期）；
 * 登录失败计数 auth:fail:username，同窗口 5 次进入锁定 1 分钟（认证方案 2.2、2.6 节）。
 */
public final class RedisAuthStore implements AuthStore, AutoCloseable {

    private static final Duration SESSION_TTL = Duration.ofHours(24);
    private static final Duration LOCK_WINDOW = Duration.ofMinutes(1);
    private static final String SESSION_KEY = "auth:session:";
    private static final String FAIL_KEY = "auth:fail:";
    private static final int LOCK_THRESHOLD = 5;

    private final RedisClient client;
    private final StatefulRedisConnection<String, String> connection;
    private final RedisCommands<String, String> sync;

    public RedisAuthStore(String uri) {
        this.client = RedisClient.create(uri);
        this.connection = client.connect();
        this.sync = connection.sync();
    }

    @Override
    public void saveSession(String token, long userId) {
        sync.set(SESSION_KEY + token, String.valueOf(userId),
                new SetArgs().ex(SESSION_TTL));
    }

    @Override
    public Long resolveUserId(String token) {
        String userId = sync.get(SESSION_KEY + token);
        if (userId == null) {
            return null;
        }
        sync.expire(SESSION_KEY + token, SESSION_TTL);
        try {
            return Long.valueOf(userId);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    @Override
    public void deleteSession(String token) {
        sync.del(SESSION_KEY + token);
    }

    @Override
    public boolean isLoginLocked(String username) {
        String count = sync.get(FAIL_KEY + username);
        return count != null && Integer.parseInt(count) >= LOCK_THRESHOLD;
    }

    @Override
    public void recordLoginFailure(String username) {
        String key = FAIL_KEY + username;
        long fails = sync.incr(key);
        if (fails == 1) {
            sync.expire(key, LOCK_WINDOW);
        }
    }

    @Override
    public void clearLoginFailures(String username) {
        sync.del(FAIL_KEY + username);
    }

    @Override
    public void close() {
        client.shutdown();
    }
}
