package com.liuzihe.interview.tool;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ToolboxTest {

    @Test
    void 注册与按名获取() {
        Toolbox toolbox = new Toolbox().register("resume", "简历解析", new ResumeParseTool());
        assertThat(toolbox.has("resume")).isTrue();
        assertThat(toolbox.get("resume", ResumeParseTool.class)).isNotNull();
        assertThat(toolbox.meta("resume").description()).isEqualTo("简历解析");
        assertThat(toolbox.has("nope")).isFalse();
    }

    @Test
    void 未注册调用抛异常() {
        assertThatThrownBy(() -> new Toolbox().invoke("nope", () -> 1))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("未注册");
    }

    @Test
    void 计时记录成功与失败() {
        Toolbox toolbox = new Toolbox().register("demo", "演示", new Object());
        String result = toolbox.invoke("demo", () -> "ok");
        assertThat(result).isEqualTo("ok");
        assertThatThrownBy(() -> toolbox.invoke("demo", () -> {
            throw new IllegalStateException("boom");
        })).isInstanceOf(IllegalStateException.class);
        assertThat(toolbox.history()).hasSize(2);
        assertThat(toolbox.history().get(0).success()).isTrue();
        assertThat(toolbox.history().get(1).success()).isFalse();
        assertThat(toolbox.history().get(1).millis()).isGreaterThanOrEqualTo(0);
    }

    @Test
    void 兜底路径显式记录() {
        Toolbox toolbox = new Toolbox().register("jd", "JD 解析", new Object());
        String result = toolbox.invokeOrFallback("jd",
                () -> {
                    throw new IllegalStateException("llm 超时");
                },
                () -> "降级结果");
        assertThat(result).isEqualTo("降级结果");
        assertThat(toolbox.history()).hasSize(2);
        assertThat(toolbox.history().get(0).success()).isFalse();
        assertThat(toolbox.history().get(1).tool()).isEqualTo("jd:fallback");
        assertThat(toolbox.history().get(1).success()).isTrue();
    }
}
