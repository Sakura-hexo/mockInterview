package com.sakura.interview.infra;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * LLM_THINKING 三值语义解析单测：只测纯函数 thinkingResolves，
 * 不经 env 读取（进程环境不可注入，llmThinkingFor 的 env 装配由真机验证覆盖）。
 */
class EnvKeysTest {

    @Test
    void false与未知值全关() {
        assertThat(EnvKeys.thinkingResolves("false", "HARD")).isFalse();
        assertThat(EnvKeys.thinkingResolves(null, "HARD")).isFalse();
        assertThat(EnvKeys.thinkingResolves("", "MEDIUM")).isFalse();
        assertThat(EnvKeys.thinkingResolves("yes", "HARD")).isFalse();
    }

    @Test
    void true全开() {
        assertThat(EnvKeys.thinkingResolves("true", "EASY")).isTrue();
        assertThat(EnvKeys.thinkingResolves("TRUE", "EASY")).isTrue();
    }

    @Test
    void auto按难度分档_中等与困难开简单关() {
        assertThat(EnvKeys.thinkingResolves("auto", "HARD")).isTrue();
        assertThat(EnvKeys.thinkingResolves("auto", "MEDIUM")).isTrue();
        assertThat(EnvKeys.thinkingResolves("auto", "EASY")).isFalse();
    }

    @Test
    void auto对大小写与未知难度宽容() {
        assertThat(EnvKeys.thinkingResolves("AUTO", "medium")).isTrue();
        assertThat(EnvKeys.thinkingResolves(" auto ", "Hard")).isTrue();
        assertThat(EnvKeys.thinkingResolves("auto", null)).isFalse();
        assertThat(EnvKeys.thinkingResolves("auto", "未知档")).isFalse();
    }
}
