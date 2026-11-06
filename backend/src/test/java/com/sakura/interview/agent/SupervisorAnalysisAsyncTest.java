package com.sakura.interview.agent;

import com.sakura.interview.infra.LlmCaller;
import com.sakura.interview.infra.StructuredOutputInvoker;
import com.sakura.interview.session.AnalysisCache;
import com.sakura.interview.session.ContextCompressor;
import com.sakura.interview.session.FakeReportStore;
import com.sakura.interview.session.FakeSessionStore;
import com.sakura.interview.session.ReportService;
import com.sakura.interview.session.SessionState;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 建会话分析异步化（2026-11-05 方案）：创建先落占位秒回，后台线程补分析回填，
 * 首轮兜底三分支（在飞等待、本线程补跑、失败降级下轮再试）与重启自愈的口径。
 */
class SupervisorAnalysisAsyncTest {

    private static final String ANALYSIS_JSON =
            "{\"digest\":\"三年 Java 后端，主导订单系统\",\"direction\":\"Java后端\","
                    + "\"skills\":[\"Spring\",\"MySQL\"],\"bonus\":[]}";

    /** 按系统提示词分流：创建分析产出摘要画像、意图分类一律面试。 */
    private static LlmCaller branchingCaller(AtomicInteger analysisCalls) {
        return (sys, user) -> {
            if (sys.contains("创建分析器")) {
                if (analysisCalls != null) {
                    analysisCalls.incrementAndGet();
                }
                return ANALYSIS_JSON;
            }
            if (sys.contains("意图分类器")) {
                return "{\"intent\":\"INTERVIEW\"}";
            }
            return "{}";
        };
    }

    /** 前三次创建分析全抛异常（耗尽结构化调用器的 3 次重试）、之后恢复：驱动后台真失败与下轮兜底。 */
    private static LlmCaller failingFirstAnalysis(AtomicInteger analysisCalls) {
        return (sys, user) -> {
            if (sys.contains("创建分析器")) {
                return analysisCalls.incrementAndGet() <= 3
                        ? throwRuntime() : ANALYSIS_JSON;
            }
            if (sys.contains("意图分类器")) {
                return "{\"intent\":\"INTERVIEW\"}";
            }
            return "{}";
        };
    }

    private static String throwRuntime() {
        throw new RuntimeException("模型超时");
    }

    private static Supervisor supervisorWith(FakeAgentRuntime runtime, FakeSessionStore sessions,
                                             LlmCaller caller, Executor executor) {
        StructuredOutputInvoker invoker = new StructuredOutputInvoker(caller);
        return new Supervisor(runtime, invoker,
                new SessionAnalyst(invoker, AnalysisCache.NONE), sessions,
                new ContextCompressor((sys, user) -> "合并摘要"),
                new ReportService(sessions, new FakeReportStore()), null, executor);
    }

    @Test
    void 同步执行器建会话即得真实摘要() {
        FakeSessionStore sessions = new FakeSessionStore();
        Supervisor supervisor = supervisorWith(new FakeAgentRuntime(c -> "x"),
                sessions, branchingCaller(null), Runnable::run);

        Supervisor.CreateResult created = supervisor.createSession("我的简历", "招 Java 后端");

        // CLI 与单测口径：execute 即同步跑完，回读拿到真实摘要，与改造前行为一致
        assertThat(created.resumeDigest()).contains("三年 Java 后端");
        assertThat(created.jdProfile().skills()).contains("Spring");
        assertThat(sessions.loadSession(created.sessionId()).orElseThrow().analysisPending()).isFalse();
    }

    @Test
    void 异步建会话秒回占位_后台跑完回填() {
        FakeSessionStore sessions = new FakeSessionStore();
        List<Runnable> queue = new ArrayList<>();
        Supervisor supervisor = supervisorWith(new FakeAgentRuntime(c -> "x"),
                sessions, branchingCaller(null), queue::add);

        Supervisor.CreateResult created = supervisor.createSession("我的简历", "招 Java 后端");

        // Web 口径：创建不等模型，返回占位（摘要空、画像只有兜底方向），原文暂存供后台取用
        assertThat(created.resumeDigest()).isEmpty();
        assertThat(created.jdProfile().direction()).isEqualTo("Java 后端");
        SessionState stored = sessions.loadSession(created.sessionId()).orElseThrow();
        assertThat(stored.analysisPending()).isTrue();
        assertThat(stored.resumeText()).isEqualTo("我的简历");
        assertThat(stored.jdText()).isEqualTo("招 Java 后端");
        assertThat(queue).hasSize(1);

        queue.forEach(Runnable::run);

        SessionState filled = sessions.loadSession(created.sessionId()).orElseThrow();
        assertThat(filled.analysisPending()).isFalse();
        assertThat(filled.resumeDigest()).contains("三年 Java 后端");
        assertThat(filled.jdProfile().skills()).contains("Spring");
        assertThat(filled.resumeText()).isNull();
    }

    @Test
    void 首轮兜底等在飞分析落库_不重复跑分析() {
        ExecutorService pool = Executors.newSingleThreadExecutor();
        try {
            AtomicInteger analysisCalls = new AtomicInteger();
            FakeAgentRuntime runtime =
                    new FakeAgentRuntime(c -> "第一题：谈谈 JVM 内存结构。\n选题");
            FakeSessionStore sessions = new FakeSessionStore();
            Supervisor supervisor = supervisorWith(runtime, sessions,
                    branchingCaller(analysisCalls), pool);

            String sessionId = supervisor.createSession("我的简历", "招 Java 后端").sessionId();
            Supervisor.SupervisorReply reply = supervisor.chat(sessionId, "回答一");

            // 后台无论已落库还是在飞，首轮出题都带真实画像，且绝不重复跑分析
            assertThat(runtime.calls().get(0).systemPrompt())
                    .contains("三年 Java 后端").contains("Spring");
            assertThat(analysisCalls.get()).isEqualTo(1);
            assertThat(reply.action()).isEqualTo(Supervisor.ACTION_QUESTION);
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    void 重启自愈_新实例首轮补跑占位会话分析() {
        FakeSessionStore sessions = new FakeSessionStore();
        AtomicInteger analysisCalls = new AtomicInteger();
        // 旧实例：提交了后台分析但队列任务永不执行，模拟建会话后进程即重启
        List<Runnable> deadQueue = new ArrayList<>();
        Supervisor old = supervisorWith(new FakeAgentRuntime(c -> "x"), sessions,
                branchingCaller(analysisCalls), deadQueue::add);
        String sessionId = old.createSession("我的简历", "招 Java 后端").sessionId();
        assertThat(sessions.loadSession(sessionId).orElseThrow().analysisPending()).isTrue();

        FakeAgentRuntime runtime =
                new FakeAgentRuntime(c -> "第一题：谈谈 JVM 内存结构。\n选题");
        Supervisor restarted = supervisorWith(runtime, sessions,
                branchingCaller(analysisCalls), Runnable::run);

        restarted.chat(sessionId, "回答一");

        // 新实例在飞集合为空，首轮兜底用落库原文补跑一次，出题带真实画像
        assertThat(runtime.calls().get(0).systemPrompt()).contains("三年 Java 后端");
        assertThat(analysisCalls.get()).isEqualTo(1);
        assertThat(sessions.loadSession(sessionId).orElseThrow().resumeDigest())
                .contains("三年 Java 后端");
    }

    @Test
    void 后台分析失败保留占位_首轮兜底重试成功() {
        FakeSessionStore sessions = new FakeSessionStore();
        AtomicInteger analysisCalls = new AtomicInteger();
        List<Runnable> queue = new ArrayList<>();
        FakeAgentRuntime runtime =
                new FakeAgentRuntime(c -> "第一题：谈谈 JVM 内存结构。\n选题");
        Supervisor supervisor = supervisorWith(runtime, sessions,
                failingFirstAnalysis(analysisCalls), queue::add);
        String sessionId = supervisor.createSession("我的简历", "招 Java 后端").sessionId();

        queue.forEach(Runnable::run);

        // 后台三次尝试全失败：pending 不清，占位与原文保留
        assertThat(analysisCalls.get()).isEqualTo(3);
        assertThat(sessions.loadSession(sessionId).orElseThrow().analysisPending()).isTrue();

        supervisor.chat(sessionId, "回答一");

        // 首轮兜底本线程重试一次即成功，出题带真实画像
        assertThat(analysisCalls.get()).isEqualTo(4);
        assertThat(runtime.calls().get(0).systemPrompt()).contains("三年 Java 后端");
        assertThat(sessions.loadSession(sessionId).orElseThrow().analysisPending()).isFalse();
    }
}
