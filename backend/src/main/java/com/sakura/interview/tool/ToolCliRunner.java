package com.sakura.interview.tool;

import com.sakura.interview.infra.AgentLlmCaller;
import com.sakura.interview.infra.StructuredOutputInvoker;

import java.util.Arrays;

/**
 * 工具链 CLI 冒烟入口：jd 子命令走真实链路验证结构化输出调用器。
 * 运行：mvn compile exec:java -Dexec.mainClass=com.sakura.interview.tool.ToolCliRunner -Dexec.args="jd 一段JD文本"
 */
public final class ToolCliRunner {

    private ToolCliRunner() {
    }

    public static void main(String[] args) {
        if (args.length < 2 || !"jd".equals(args[0])) {
            System.err.println("用法：jd 一段JD文本");
            System.exit(2);
        }
        String jd = String.join(" ", Arrays.copyOfRange(args, 1, args.length));
        StructuredOutputInvoker invoker = new StructuredOutputInvoker(new AgentLlmCaller());
        JdParseTool tool = new JdParseTool(invoker);
        long start = System.nanoTime();
        JdProfile profile = tool.parse(jd);
        long millis = (System.nanoTime() - start) / 1_000_000;
        System.out.println("方向：" + profile.direction());
        System.out.println("技能要求：" + profile.skills());
        System.out.println("加分项：" + profile.bonus());
        System.out.println("耗时 " + millis + "ms | 调用统计 " + invoker.stats());
    }
}
