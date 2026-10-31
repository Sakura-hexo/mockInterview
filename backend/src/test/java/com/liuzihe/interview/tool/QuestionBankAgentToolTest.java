package com.liuzihe.interview.tool;

import com.liuzihe.interview.rag.SearchHit;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.message.ToolResultBlock;
import io.agentscope.core.message.ToolUseBlock;
import io.agentscope.core.tool.ToolCallParam;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class QuestionBankAgentToolTest {

    @Test
    void 元信息与参数schema() {
        QuestionBankAgentTool tool = new QuestionBankAgentTool(
                new QuestionBankTool((query, topK) -> List.of()));
        assertThat(tool.getName()).isEqualTo("search_question_bank");
        assertThat(tool.isReadOnly()).isTrue();
        assertThat(tool.getDescription()).contains("题库");
        Map<String, Object> schema = tool.getParameters();
        assertThat(schema).containsKeys("type", "properties", "required");
    }

    @Test
    void 调用返回携带调用id与检索结果() {
        QuestionBankAgentTool tool = new QuestionBankAgentTool(new QuestionBankTool(
                (query, topK) -> List.of(new SearchHit("QB-001", "HashMap 底层", "Java集合", "AI生成", 0.1f))));
        ToolCallParam param = ToolCallParam.builder()
                .toolUseBlock(new ToolUseBlock("call-9", QuestionBankAgentTool.NAME,
                        Map.of("query", "HashMap")))
                .build();

        ToolResultBlock result = tool.callAsync(param).block();

        assertThat(result).isNotNull();
        assertThat(result.getId()).isEqualTo("call-9");
        assertThat(result.getName()).isEqualTo(QuestionBankAgentTool.NAME);
        String text = result.getOutput().stream()
                .map(block -> block instanceof TextBlock tb ? tb.getText() : "")
                .reduce("", String::concat);
        assertThat(text).contains("QB-001").contains("HashMap 底层");
    }
}
