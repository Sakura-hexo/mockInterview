package com.sakura.interview.rag;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 题库解析单测：管理端上传走 parseText（内存文本），格式与文件版完全一致。
 */
class QuestionBankParserTest {

    private static final String VALID_BANK = """
            ## MQ-001
            题干：索引失效的常见场景有哪些
            要点：最左前缀、函数包裹、隐式类型转换、前导通配符
            标签：MySQL
            来源：个人笔记

            ## JU-002
            题干：线程池核心参数的作用
            要点：corePoolSize、maximumPoolSize、队列、拒绝策略
            标签：Java 并发
            来源：付费笔记
            """;

    @Test
    void 解析多题并保持字段与顺序() {
        List<BankQuestion> questions = QuestionBankParser.parseText(VALID_BANK);

        assertThat(questions).hasSize(2);
        assertThat(questions.get(0).id()).isEqualTo("MQ-001");
        assertThat(questions.get(0).question()).isEqualTo("索引失效的常见场景有哪些");
        assertThat(questions.get(0).tags()).isEqualTo("MySQL");
        assertThat(questions.get(1).id()).isEqualTo("JU-002");
        assertThat(questions.get(1).source()).isEqualTo("付费笔记");
    }

    @Test
    void 字段缺失抛出并指明题号() {
        assertThatThrownBy(() -> QuestionBankParser.parseText("""
                ## MQ-003
                题干：只有题干没有其余字段
                """))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("MQ-003");
    }

    @Test
    void 无题目与空内容都拒绝() {
        assertThatThrownBy(() -> QuestionBankParser.parseText("随便一段说明文字"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("没有解析到任何题目");
        assertThatThrownBy(() -> QuestionBankParser.parseText(null))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void searchText包含题干要点与标签供双路检索() {
        BankQuestion question = QuestionBankParser.parseText(VALID_BANK).get(0);
        assertThat(question.searchText())
                .contains("索引失效")
                .contains("最左前缀")
                .contains("MySQL");
    }
}
