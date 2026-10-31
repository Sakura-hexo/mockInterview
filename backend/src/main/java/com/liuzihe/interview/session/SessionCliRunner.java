package com.liuzihe.interview.session;

import com.liuzihe.interview.infra.MySqlDb;
import com.liuzihe.interview.infra.PersistentSessionStore;
import com.liuzihe.interview.infra.RedisSessionCache;
import com.zaxxer.hikari.HikariDataSource;

import java.util.List;
import java.util.UUID;

/**
 * 会话层命令行入口：init-db 建库建表，selfcheck 全链路自证
 * （追加、窗口读取、摘要存取、缓存丢失后从 MySQL 重建）。
 *
 * 运行：mvn compile exec:java
 *   -Dexec.mainClass=com.liuzihe.interview.session.SessionCliRunner
 *   -Dexec.args="init-db 或 selfcheck"
 */
public final class SessionCliRunner {

    private SessionCliRunner() {
    }

    public static void main(String[] args) {
        if (args.length < 1) {
            System.err.println("用法：init-db（建库建表） | selfcheck（全链路自检，需 MySQL 与 Redis 在运行）");
            System.exit(2);
        }
        switch (args[0]) {
            case "init-db" -> initDb();
            case "selfcheck" -> selfcheck();
            default -> {
                System.err.println("未知子命令：" + args[0]);
                System.exit(2);
            }
        }
    }

    private static void initDb() {
        try (HikariDataSource ds = MySqlDb.dataSource()) {
            MySqlDb.init(ds);
            System.out.println("会话表已就绪：t_session、t_message、t_context_summary");
        }
    }

    private static void selfcheck() {
        try (HikariDataSource ds = MySqlDb.dataSource();
             RedisSessionCache redis = new RedisSessionCache(com.liuzihe.interview.infra.EnvKeys.redisUri());
             PersistentSessionStore store = new PersistentSessionStore(ds, redis)) {
            String id = "s-selfcheck-" + UUID.randomUUID().toString().substring(0, 8);
            for (int i = 1; i <= 25; i++) {
                store.appendMessage(id, i % 2 == 1 ? "user" : "interviewer",
                        "自检消息" + i, i, i * 2);
            }
            List<StoredMessage> window = store.readWindow(id, 20);
            check(window.size() == 20, "窗口读取 20 条");
            check(window.get(0).seq() == 6 && window.get(19).seq() == 25,
                    "窗口边界为最近 20 条（首 seq 6，末 seq 25）");
            check(store.readBatch(id, 20, 10).size() == 5, "批次读取 seq 大于 20 仅 5 条");

            store.saveSummary(id, "自检前情摘要", 10);
            check(store.loadSummary(id).orElseThrow().text().equals("自检前情摘要")
                            && store.loadSummary(id).orElseThrow().coveredSeq() == 10,
                    "摘要存取一致");

            store.evictCache(id);
            List<StoredMessage> rebuilt = store.readWindow(id, 20);
            check(rebuilt.equals(window), "缓存丢失后从 MySQL 重建的窗口与原窗口一致");
            check(store.loadSummary(id).orElseThrow().text().equals("自检前情摘要"),
                    "缓存丢失后摘要从 MySQL 重建");

            store.deleteSession(id);
            check(store.readWindow(id, 20).isEmpty() && store.loadSummary(id).isEmpty(),
                    "会话清理干净");
            System.out.println("selfcheck 全部通过。");
        }
    }

    private static void check(boolean condition, String description) {
        if (!condition) {
            throw new IllegalStateException("selfcheck 失败：" + description);
        }
        System.out.println("通过：" + description);
    }
}
