package com.liuzihe.interview.agent;

import com.liuzihe.interview.infra.LlmCaller;
import com.liuzihe.interview.infra.StructuredOutputInvoker;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class RouterTest {

    @Test
    void 复盘关键词规则兜底不调模型() {
        LlmCaller bomb = (sys, user) -> {
            throw new IllegalStateException("规则兜底不应调用模型");
        };
        Router router = new Router(new StructuredOutputInvoker(bomb));
        assertThat(router.route("我想结束面试")).isEqualTo(Intent.REPORT);
        assertThat(router.route("给我出复盘报告")).isEqualTo(Intent.REPORT);
    }

    @Test
    void 结构化分类闲聊与面试() {
        Router router = new Router(new StructuredOutputInvoker((sys, user) ->
                user.contains("天气") ? "{\"intent\":\"CHAT\"}" : "{\"intent\":\"INTERVIEW\"}"));
        assertThat(router.route("今天天气不错")).isEqualTo(Intent.CHAT);
        assertThat(router.route("HashMap 是数组加链表")).isEqualTo(Intent.INTERVIEW);
    }

    @Test
    void 分类输出非法回退面试() {
        Router router = new Router(new StructuredOutputInvoker((sys, user) -> "not json"));
        assertThat(router.route("随便说点什么")).isEqualTo(Intent.INTERVIEW);
    }
}
