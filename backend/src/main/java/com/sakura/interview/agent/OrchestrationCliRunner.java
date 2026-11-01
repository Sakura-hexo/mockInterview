package com.sakura.interview.agent;

import com.sakura.interview.infra.AgentLlmCaller;
import com.sakura.interview.infra.AgentScopeRuntime;
import com.sakura.interview.infra.EmbeddingModels;
import com.sakura.interview.infra.EnvKeys;
import com.sakura.interview.infra.MySqlDb;
import com.sakura.interview.infra.MySqlReportStore;
import com.sakura.interview.infra.PersistentSessionStore;
import com.sakura.interview.infra.RedisSessionCache;
import com.sakura.interview.infra.StructuredOutputInvoker;
import com.sakura.interview.rag.MilvusQuestionStore;
import com.sakura.interview.session.ContextCompressor;
import com.sakura.interview.session.ReportGenerator;
import com.sakura.interview.session.ReportService;
import com.sakura.interview.session.ReportWorker;
import com.sakura.interview.session.SessionState;
import com.sakura.interview.tool.QuestionBankTool;
import com.zaxxer.hikari.HikariDataSource;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * 编排 CLI：新会话或 --resume 恢复进行多轮对话，验证路由分流、追问换题、
 * 上下文压缩（长会话不失忆）与进程重启恢复。每轮打印 token 用量与耗时。
 * 运行：mvn compile exec:java
 *   -Dexec.mainClass=com.sakura.interview.agent.OrchestrationCliRunner
 *   -Dexec.args="简历文本文件路径 JD文本文件路径"  或  "-Dexec.args=--resume 会话id"
 */
public final class OrchestrationCliRunner {

    private OrchestrationCliRunner() {
    }

    public static void main(String[] args) throws Exception {
        String resumeId = null;
        if (args.length == 2 && "--resume".equals(args[0])) {
            resumeId = args[1];
        } else if (args.length != 2) {
            System.err.println("用法：\n  新会话：orchestrate <简历文本文件路径> <JD文本文件路径>"
                    + "\n  恢复会话：orchestrate --resume <会话id>");
            System.exit(2);
        }
        try (HikariDataSource ds = MySqlDb.dataSource();
             RedisSessionCache redis = new RedisSessionCache(EnvKeys.redisUri());
             PersistentSessionStore store = new PersistentSessionStore(ds, redis);
             MilvusQuestionStore questionStore =
                     new MilvusQuestionStore(EmbeddingModels.embedding())) {
            AgentScopeRuntime runtime = new AgentScopeRuntime();
            StructuredOutputInvoker invoker = new StructuredOutputInvoker(new AgentLlmCaller());
            MySqlReportStore reportStore = new MySqlReportStore(ds);
            Supervisor supervisor = new Supervisor(runtime, invoker, store,
                    new ContextCompressor(new AgentLlmCaller()),
                    new ReportService(store, reportStore),
                    new QuestionBankTool(questionStore), Runnable::run);
            try (ReportWorker worker = new ReportWorker(reportStore,
                    new ReportGenerator(invoker, store)).start()) {
                runLoop(supervisor, args, resumeId);
            }
        }
    }

    private static void runLoop(Supervisor supervisor, String[] args, String resumeId) throws Exception {
        String sessionId;
        long round;
        if (resumeId != null) {
            SessionState restored = supervisor.state(resumeId);
            sessionId = restored.sessionId();
            round = restored.round();
            System.out.println("会话已恢复：" + sessionId + " | 已面试 " + round + " 轮");
            System.out.println("简历摘要：" + restored.resumeDigest());
        } else {
            String resumeText = Files.readString(Path.of(args[0]));
            String jdText = Files.readString(Path.of(args[1]));
            Supervisor.CreateResult created = supervisor.createSession(resumeText, jdText);
            sessionId = created.sessionId();
            round = 0;
            System.out.println("会话已创建：" + sessionId);
            System.out.println("简历摘要：" + created.resumeDigest());
            System.out.println("岗位要点：" + created.jdProfile().direction()
                    + " | " + created.jdProfile().skills());
        }
        System.out.println("输入回答开始面试；输入 生成报告 或 结束面试 触发复盘；输入 exit、quit 或 退出 结束。");

        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(System.in, StandardCharsets.UTF_8))) {
            while (true) {
                System.out.print("\n[候选人] > ");
                String input = reader.readLine();
                if (input == null || input.isBlank()) {
                    continue;
                }
                String text = input.strip();
                if ("exit".equalsIgnoreCase(text) || "quit".equalsIgnoreCase(text) || "退出".equals(text)) {
                    System.out.println("编排验证结束。会话 id：" + sessionId + "（可用 --resume 恢复）");
                    break;
                }
                round++;
                long start = System.nanoTime();
                Supervisor.SupervisorReply reply = supervisor.chat(sessionId, text);
                long millis = (System.nanoTime() - start) / 1_000_000;
                System.out.println("\n[意图 " + reply.intent() + "] [面试官] " + reply.reply());
                System.out.printf("[统计] 第 %d 轮 | 耗时 %d ms | 输入 token: %d | 输出 token: %d%n",
                        round, millis, reply.inputTokens(), reply.outputTokens());
            }
        }
    }
}
