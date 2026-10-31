package com.sakura.interview.infra;

import io.lettuce.core.RedisClient;
import io.lettuce.core.SetArgs;
import io.lettuce.core.api.StatefulRedisConnection;
import io.lettuce.core.api.sync.RedisCommands;

import java.time.Duration;
import java.util.List;

/**
 * Redis 热存：消息窗口列表与滚动摘要两个 key，TTL 24 小时、每次读写续期。
 * 只做缓存不做事实源，任何 key 丢失都由 PersistentSessionStore 从 MySQL 重建。
 */
public final class RedisSessionCache implements AutoCloseable {

    private static final Duration TTL = Duration.ofHours(24);
    private static final String MSGS_KEY = "session:msgs:";
    private static final String SUMMARY_KEY = "session:summary:";

    private final RedisClient client;
    private final StatefulRedisConnection<String, String> connection;
    private final RedisCommands<String, String> sync;

    public RedisSessionCache(String uri) {
        this.client = RedisClient.create(uri);
        this.connection = client.connect();
        this.sync = connection.sync();
    }

    /** 追加一条消息 JSON 并续期。 */
    public void appendMessage(String sessionId, String json) {
        sync.rpush(MSGS_KEY + sessionId, json);
        sync.expire(MSGS_KEY + sessionId, TTL);
    }

    /** 最近 limit 条消息 JSON，key 不存在或已过期时返回空列表。 */
    public List<String> readMessages(String sessionId, int limit) {
        if (limit <= 0) {
            return List.of();
        }
        List<String> tail = sync.lrange(MSGS_KEY + sessionId, -limit, -1);
        if (!tail.isEmpty()) {
            sync.expire(MSGS_KEY + sessionId, TTL);
        }
        return tail;
    }

    /** 缓存丢失后用全量消息重建列表。 */
    public void repopulateMessages(String sessionId, List<String> jsons) {
        String key = MSGS_KEY + sessionId;
        sync.del(key);
        if (!jsons.isEmpty()) {
            sync.rpush(key, jsons.toArray(String[]::new));
            sync.expire(key, TTL);
        }
    }

    public void saveSummary(String sessionId, String json) {
        sync.set(SUMMARY_KEY + sessionId, json, new SetArgs().ex(TTL));
    }

    /** 摘要 JSON，不存在返回 null；命中即续期。 */
    public String readSummary(String sessionId) {
        String json = sync.get(SUMMARY_KEY + sessionId);
        if (json != null) {
            sync.expire(SUMMARY_KEY + sessionId, TTL);
        }
        return json;
    }

    /** 删除该会话两个缓存 key，供自检模拟缓存丢失。 */
    public void evict(String sessionId) {
        sync.del(MSGS_KEY + sessionId, SUMMARY_KEY + sessionId);
    }

    @Override
    public void close() {
        client.shutdown();
    }
}
