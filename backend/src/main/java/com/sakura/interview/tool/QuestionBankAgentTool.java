package com.sakura.interview.tool;

import io.agentscope.core.message.TextBlock;
import io.agentscope.core.message.ToolResultBlock;
import io.agentscope.core.message.ToolUseBlock;
import io.agentscope.core.tool.AgentTool;
import io.agentscope.core.tool.ToolCallParam;
import reactor.core.publisher.Mono;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 题库工具的智能体适配：直接实现 AgentTool 接口而非 @Tool 注解反射，
 * 参数绑定与调用 id 显式可控（前置核验结论：反射参数名存在退化风险）。
 * 面试官智能体经 Toolkit.registerAgentTool 挂载。
 */
public final class QuestionBankAgentTool implements AgentTool {

    public static final String NAME = "search_question_bank";

    private final QuestionBankTool delegate;

    public QuestionBankAgentTool(QuestionBankTool delegate) {
        this.delegate = delegate;
    }

    @Override
    public String getName() {
        return NAME;
    }

    @Override
    public String getDescription() {
        return "按查询检索 Java 面试题库，返回最相关的题目列表供出题参考，query 为知识点或题目主题。";
    }

    @Override
    public boolean isReadOnly() {
        return true;
    }

    @Override
    public Map<String, Object> getParameters() {
        Map<String, Object> query = new HashMap<>();
        query.put("type", "string");
        query.put("description", "检索查询词，例如 HashMap、索引失效、线程池");
        Map<String, Object> schema = new HashMap<>();
        schema.put("type", "object");
        schema.put("properties", Map.of("query", query));
        schema.put("required", List.of("query"));
        return schema;
    }

    @Override
    public Mono<ToolResultBlock> callAsync(ToolCallParam param) {
        return Mono.fromCallable(() -> {
            ToolUseBlock use = param.getToolUseBlock();
            Map<String, Object> input = param.getInput() != null ? param.getInput() : use.getInput();
            Object query = input == null ? null : input.get("query");
            String result = delegate.retrieveAsText(query == null ? "" : String.valueOf(query));
            return new ToolResultBlock(use.getId(), NAME, TextBlock.builder().text(result).build());
        });
    }
}
