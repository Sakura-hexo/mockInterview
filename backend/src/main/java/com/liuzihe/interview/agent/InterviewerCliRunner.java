package com.liuzihe.interview.agent;

import com.liuzihe.interview.infra.ChatModels;
import com.liuzihe.interview.infra.EnvKeys;
import io.agentscope.core.ReActAgent;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.message.Msg;
import io.agentscope.core.model.ChatUsage;
import io.agentscope.core.model.Model;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.time.Duration;

/**
 * 最小面试官智能体的命令行入口：逐轮读取候选人输入，输出面试官回复，
 * 每轮打印 token 消耗与耗时，为后续 AOP 埋点打基础。
 *
 * 运行方式：在 backend 目录执行 mvn compile exec:java
 */
public final class InterviewerCliRunner {

    private static final Duration CALL_TIMEOUT = Duration.ofSeconds(120);

    public static void main(String[] args) {
        Model model = ChatModels.chat();
        ReActAgent interviewer = InterviewerAgent.create(model);
        RuntimeContext context = RuntimeContext.builder()
                .userId("cli-user")
                .sessionId("interview-cli")
                .build();

        System.out.println("=== AI 模拟面试官（最小版） ===");
        System.out.println("模型源 " + EnvKeys.modelProvider() + " / " + model.getModelName());
        System.out.println("输入「开始面试」获得第一个问题；输入 exit、quit 或 退出 结束。");
        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(System.in, StandardCharsets.UTF_8))) {
            int round = 0;
            while (true) {
                System.out.print("\n[候选人] > ");
                String input = reader.readLine();
                if (input == null || input.isBlank()) {
                    continue;
                }
                String text = input.trim();
                if ("exit".equalsIgnoreCase(text) || "quit".equalsIgnoreCase(text) || "退出".equals(text)) {
                    System.out.println("面试结束。");
                    break;
                }
                round++;
                long start = System.nanoTime();
                Msg reply = interviewer.call(text, context).block(CALL_TIMEOUT);
                long latencyMs = (System.nanoTime() - start) / 1_000_000;
                if (reply == null) {
                    System.out.println("[面试官] （本轮无回复）");
                    continue;
                }
                System.out.println("\n[面试官] " + reply.getTextContent());
                ChatUsage usage = reply.getUsage();
                if (usage != null) {
                    System.out.printf("[统计] 第 %d 轮 | 输入 token: %d | 输出 token: %d | 耗时: %d ms%n",
                            round, usage.getInputTokens(), usage.getOutputTokens(), latencyMs);
                } else {
                    System.out.printf("[统计] 第 %d 轮 | 无用量数据 | 耗时: %d ms%n", round, latencyMs);
                }
            }
        } catch (Exception e) {
            System.err.println("面试官智能体运行异常：" + e.getMessage());
            e.printStackTrace();
            System.exit(1);
        }
    }
}
