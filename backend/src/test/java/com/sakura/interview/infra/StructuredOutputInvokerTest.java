package com.sakura.interview.infra;

import com.google.gson.JsonObject;
import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class StructuredOutputInvokerTest {

    @Test
    void 正常解析一次成功() {
        StructuredOutputInvoker invoker = new StructuredOutputInvoker(
                (sys, user) -> "{\"direction\":\"Java\"}");
        JsonObject obj = invoker.invoke("系统", "任务", JsonObject.class);
        assertThat(obj.get("direction").getAsString()).isEqualTo("Java");
        assertThat(invoker.stats().calls()).isEqualTo(1);
        assertThat(invoker.stats().retries()).isZero();
        assertThat(invoker.stats().failures()).isZero();
    }

    @Test
    void 剥离代码围栏() {
        StructuredOutputInvoker invoker = new StructuredOutputInvoker(
                (sys, user) -> "```json\n{\"a\":1}\n```");
        JsonObject obj = invoker.invoke("系统", "任务", JsonObject.class);
        assertThat(obj.get("a").getAsInt()).isEqualTo(1);
        assertThat(invoker.stats().retries()).isZero();
    }

    @Test
    void 尾逗号本地修复() {
        StructuredOutputInvoker invoker = new StructuredOutputInvoker(
                (sys, user) -> "{\"a\":1,\"b\":[1,2,],}");
        JsonObject obj = invoker.invoke("系统", "任务", JsonObject.class);
        assertThat(obj.get("b").getAsJsonArray()).hasSize(2);
        assertThat(invoker.stats().retries()).isZero();
    }

    @Test
    void 解析失败带原因重试后成功() {
        AtomicInteger counter = new AtomicInteger();
        StructuredOutputInvoker invoker = new StructuredOutputInvoker((sys, user) -> {
            if (counter.incrementAndGet() == 1) {
                return "抱歉，我无法输出 JSON";
            }
            assertThat(user).contains("上次输出解析失败");
            return "{\"ok\":true}";
        });
        JsonObject obj = invoker.invoke("系统", "任务", JsonObject.class);
        assertThat(obj.get("ok").getAsBoolean()).isTrue();
        assertThat(invoker.stats().calls()).isEqualTo(2);
        assertThat(invoker.stats().retries()).isEqualTo(1);
    }

    @Test
    void 重试耗尽抛业务异常() {
        StructuredOutputInvoker invoker = new StructuredOutputInvoker((sys, user) -> "还是不行");
        assertThatThrownBy(() -> invoker.invoke("系统", "任务", JsonObject.class))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("已尝试 3 次");
        assertThat(invoker.stats().failures()).isEqualTo(1);
        assertThat(invoker.stats().calls()).isEqualTo(3);
    }

    @Test
    void 调用异常同样计入重试() {
        StructuredOutputInvoker invoker = new StructuredOutputInvoker((sys, user) -> {
            throw new IllegalStateException("网络抖动");
        });
        assertThatThrownBy(() -> invoker.invoke("系统", "任务", JsonObject.class))
                .isInstanceOf(IllegalStateException.class);
        assertThat(invoker.stats().calls()).isEqualTo(3);
    }

    @Test
    void extractJson边界行为() {
        assertThat(StructuredOutputInvoker.extractJson(null)).isNull();
        assertThat(StructuredOutputInvoker.extractJson("没有大括号")).isNull();
        assertThat(StructuredOutputInvoker.extractJson("前缀{\"a\":1}后缀")).isEqualTo("{\"a\":1}");
    }
}
