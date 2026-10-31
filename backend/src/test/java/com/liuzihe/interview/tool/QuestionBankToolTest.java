package com.liuzihe.interview.tool;

import com.liuzihe.interview.rag.SearchHit;
import com.liuzihe.interview.rag.SearchTiers;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class QuestionBankToolTest {

    @Test
    void 命中格式化为文本块() {
        QuestionBankTool tool = new QuestionBankTool((query, topK) -> List.of(
                new SearchHit("QB-001", "HashMap 底层结构是怎样的？", "Java集合,HashMap", "AI生成", 0.03f)));
        String text = tool.retrieveAsText("HashMap 扩容");
        assertThat(text).contains("QB-001").contains("HashMap 底层结构").contains("Java集合");
    }

    @Test
    void 无命中返回提示语() {
        QuestionBankTool tool = new QuestionBankTool((query, topK) -> List.of());
        assertThat(tool.retrieveAsText("量子计算")).contains("题库无命中");
    }

    @Test
    void 短查询按分档取三() {
        List<Integer> capturedTopK = new ArrayList<>();
        QuestionBankTool tool = new QuestionBankTool((query, topK) -> {
            capturedTopK.add(topK);
            return List.of();
        });
        tool.retrieve("HashMap");
        assertThat(capturedTopK).containsExactly(SearchTiers.SHORT_TOPK);
    }

    @Test
    void 长查询按分档取五() {
        List<Integer> capturedTopK = new ArrayList<>();
        QuestionBankTool tool = new QuestionBankTool((query, topK) -> {
            capturedTopK.add(topK);
            return List.of();
        });
        tool.retrieve("请围绕并发编程的内存可见性出题");
        assertThat(capturedTopK).containsExactly(SearchTiers.LONG_TOPK);
    }
}
