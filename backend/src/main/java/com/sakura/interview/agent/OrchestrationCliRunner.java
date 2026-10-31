package com.sakura.interview.agent;

import com.sakura.interview.infra.AgentLlmCaller;
import com.sakura.interview.infra.AgentScopeRuntime;
import com.sakura.interview.infra.EmbeddingModels;
import com.sakura.interview.infra.StructuredOutputInvoker;
import com.sakura.interview.rag.MilvusQuestionStore;
import com.sakura.interview.tool.QuestionBankAgentTool;
import com.sakura.interview.tool.QuestionBankTool;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/**
 * 步骤 4 编排 CLI：建会话后进入多轮对话，验证路由分流、追问换题与已问题目防重复。
 * 运行：mvn compile exec:java
 *   -Dexec.mainClass=com.sakura.interview.agent.OrchestrationCliRunner
 *   -Dexec.args="简历文本文件路径 JD文本文件路径"
 */
public final class OrchestrationCliRunner {

    private OrchestrationCliRunner() {
    }

    public static void main(String[] args) throws Exception {
        if (args.length < 2) {
            System.err.println("用法：orchestrate <简历文本文件路径> <JD文本文件路径>");
            System.exit(2);
        }
        String resumeText = Files.readString(Path.of(args[0]));
        String jdText = Files.readString(Path.of(args[1]));
        try (MilvusQuestionStore store = new MilvusQuestionStore(EmbeddingModels.embedding())) {
            QuestionBankAgentTool bankTool = new QuestionBankAgentTool(new QuestionBankTool(store));
            AgentScopeRuntime runtime = new AgentScopeRuntime(List.of(bankTool));
            Supervisor supervisor = new Supervisor(runtime, new StructuredOutputInvoker(new AgentLlmCaller()));

            Supervisor.CreateResult created = supervisor.createSession(resumeText, jdText);
            System.out.println("会话已创建：" + created.sessionId());
            System.out.println("简历摘要：" + created.resumeDigest());
            System.out.println("岗位要点：" + created.jdProfile().direction()
                    + " | " + created.jdProfile().skills());
            System.out.println("输入回答开始面试；输入 exit、quit 或 退出 结束。");

            try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(System.in, StandardCharsets.UTF_8))) {
                int round = 0;
                while (true) {
                    System.out.print("\n[候选人] > ");
                    String input = reader.readLine();
                    if (input == null || input.isBlank()) {
                        continue;
                    }
                    String text = input.strip();
                    if ("exit".equalsIgnoreCase(text) || "quit".equalsIgnoreCase(text) || "退出".equals(text)) {
                        System.out.println("编排验证结束。");
                        break;
                    }
                    round++;
                    long start = System.nanoTime();
                    Supervisor.SupervisorReply reply = supervisor.chat(created.sessionId(), text);
                    long millis = (System.nanoTime() - start) / 1_000_000;
                    System.out.println("\n[意图 " + reply.intent() + "] [面试官] " + reply.reply());
                    System.out.printf("[统计] 第 %d 轮 | 耗时 %d ms%n", round, millis);
                }
            }
        }
    }
}
