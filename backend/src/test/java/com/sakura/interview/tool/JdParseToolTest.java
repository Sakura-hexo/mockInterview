package com.sakura.interview.tool;

import com.sakura.interview.infra.StructuredOutputInvoker;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class JdParseToolTest {

    @Test
    void 成功解析结构化要点() {
        StructuredOutputInvoker invoker = new StructuredOutputInvoker((sys, user) ->
                "{\"direction\":\"Java后端\",\"skills\":[\"Java\",\"Spring\",\"MySQL\"],\"bonus\":[\"有AI项目经验\"]}");
        JdProfile profile = new JdParseTool(invoker).parse("招 Java 后端，要求会 Spring 和 MySQL");
        assertThat(profile.direction()).isEqualTo("Java后端");
        assertThat(profile.skills()).containsExactly("Java", "Spring", "MySQL");
        assertThat(profile.bonus()).containsExactly("有AI项目经验");
    }

    @Test
    void 解析失败显式降级为原文要点() {
        StructuredOutputInvoker invoker = new StructuredOutputInvoker((sys, user) -> "完全不是 JSON");
        JdProfile profile = new JdParseTool(invoker).parse("招 Java 后端工程师");
        assertThat(profile.direction()).isEqualTo("解析降级");
        assertThat(profile.skills()).containsExactly("招 Java 后端工程师");
        assertThat(profile.bonus()).isEmpty();
    }

    @Test
    void 缺少skills字段视为失败降级() {
        StructuredOutputInvoker invoker = new StructuredOutputInvoker(
                (sys, user) -> "{\"direction\":\"只有方向\"}");
        JdProfile profile = new JdParseTool(invoker).parse("招 Java 后端");
        assertThat(profile.direction()).isEqualTo("解析降级");
    }

    @Test
    void 空文本与超长抛异常() {
        JdParseTool tool = new JdParseTool(new StructuredOutputInvoker((s, u) -> "{}"));
        assertThatThrownBy(() -> tool.parse("  ")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> tool.parse("字".repeat(21 * 1024)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("20KB");
    }
}
