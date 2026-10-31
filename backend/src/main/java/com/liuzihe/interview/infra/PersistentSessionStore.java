package com.liuzihe.interview.infra;

import com.google.gson.Gson;
import com.liuzihe.interview.session.SessionState;
import com.liuzihe.interview.session.SessionStore;
import com.liuzihe.interview.session.SessionSummary;
import com.liuzihe.interview.session.StoredMessage;

import javax.sql.DataSource;
import java.util.List;
import java.util.Optional;

/**
 * SessionStore 生产实现：MySQL 事实源加 Redis 热存。
 * 一致性顺序——写先 MySQL 后 Redis，Redis 写失败删 key 并告警（下次读取从库重建）；
 * 读 Redis 优先，未命中从 MySQL 回填再返回。会话元数据每轮按主键直查 MySQL，不进缓存。
 */
public final class PersistentSessionStore implements SessionStore, AutoCloseable {

    private final MySqlSessionStore mysql;
    private final RedisSessionCache redis;
    private final Gson gson = new Gson();

    public PersistentSessionStore(DataSource dataSource, RedisSessionCache redis) {
        this.mysql = new MySqlSessionStore(dataSource);
        this.redis = redis;
    }

    @Override
    public void saveSession(SessionState state) {
        mysql.saveSession(state);
    }

    @Override
    public Optional<SessionState> loadSession(String sessionId) {
        return mysql.loadSession(sessionId);
    }

    @Override
    public int appendMessage(String sessionId, String role, String text,
                             long inputTokens, long outputTokens) {
        int seq = mysql.appendMessage(sessionId, role, text, inputTokens, outputTokens);
        try {
            redis.appendMessage(sessionId,
                    gson.toJson(new StoredMessage(seq, role, text, inputTokens, outputTokens)));
        } catch (RuntimeException e) {
            warnAndEvict(sessionId, e);
        }
        return seq;
    }

    @Override
    public List<StoredMessage> readWindow(String sessionId, int limit) {
        try {
            List<String> cached = redis.readMessages(sessionId, limit);
            if (!cached.isEmpty()) {
                return cached.stream().map(this::parseMessage).toList();
            }
        } catch (RuntimeException e) {
            System.err.printf("[会话缓存] 读取失败走回源：%s%n", e.getMessage());
        }
        List<StoredMessage> all = mysql.readAll(sessionId);
        if (!all.isEmpty()) {
            try {
                redis.repopulateMessages(sessionId,
                        all.stream().map(gson::toJson).toList());
            } catch (RuntimeException e) {
                System.err.printf("[会话缓存] 回填失败可容忍：%s%n", e.getMessage());
            }
        }
        return all.size() <= limit ? all : all.subList(all.size() - limit, all.size());
    }

    @Override
    public List<StoredMessage> readBatch(String sessionId, int afterSeq, int limit) {
        return mysql.readBatch(sessionId, afterSeq, limit);
    }

    @Override
    public Optional<SessionSummary> loadSummary(String sessionId) {
        try {
            String cached = redis.readSummary(sessionId);
            if (cached != null && !cached.isBlank()) {
                return Optional.of(gson.fromJson(cached, SessionSummary.class));
            }
        } catch (RuntimeException e) {
            System.err.printf("[会话缓存] 摘要读取失败走回源：%s%n", e.getMessage());
        }
        Optional<SessionSummary> fromDb = mysql.loadSummary(sessionId);
        fromDb.ifPresent(summary -> {
            try {
                redis.saveSummary(sessionId, gson.toJson(summary));
            } catch (RuntimeException e) {
                warnAndEvict(sessionId, e);
            }
        });
        return fromDb;
    }

    @Override
    public void saveSummary(String sessionId, String text, int coveredSeq) {
        mysql.saveSummary(sessionId, text, coveredSeq);
        try {
            redis.saveSummary(sessionId, gson.toJson(new SessionSummary(text, coveredSeq)));
        } catch (RuntimeException e) {
            warnAndEvict(sessionId, e);
        }
    }

    @Override
    public void deleteSession(String sessionId) {
        mysql.deleteSession(sessionId);
        try {
            redis.evict(sessionId);
        } catch (RuntimeException e) {
            System.err.printf("[会话缓存] 清理失败可容忍：%s%n", e.getMessage());
        }
    }

    /** 删除该会话两个缓存 key（保留 MySQL 数据），供自检模拟缓存丢失。 */
    public void evictCache(String sessionId) {
        redis.evict(sessionId);
    }

    @Override
    public void close() {
        redis.close();
    }

    private StoredMessage parseMessage(String json) {
        return gson.fromJson(json, StoredMessage.class);
    }

    private void warnAndEvict(String sessionId, RuntimeException cause) {
        System.err.printf("[会话缓存] 写入失败删除缓存待重建：%s%n", cause.getMessage());
        try {
            redis.evict(sessionId);
        } catch (RuntimeException ignored) {
            // Redis 不可用时重建留给下一次读取，不影响主流程
        }
    }
}
