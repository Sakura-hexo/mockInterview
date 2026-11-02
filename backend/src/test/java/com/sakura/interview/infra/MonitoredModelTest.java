package com.sakura.interview.infra;

import io.agentscope.core.message.Msg;
import io.agentscope.core.model.ChatResponse;
import io.agentscope.core.model.GenerateOptions;
import io.agentscope.core.model.Model;
import io.agentscope.core.model.ToolSchema;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerConfig;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Flux;

import java.time.Duration;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class MonitoredModelTest {

    /** 恒定失败的假模型：直连熔断器配置复刻 ChatModels 的窗口与阈值。 */
    private static Model failingModel() {
        return new Model() {
            @Override
            public Flux<ChatResponse> stream(List<Msg> messages, List<ToolSchema> tools,
                                             GenerateOptions options) {
                return Flux.error(new RuntimeException("relay down"));
            }

            @Override
            public String getModelName() {
                return "fake";
            }
        };
    }

    private static CircuitBreaker breaker() {
        return CircuitBreaker.of("test-llm", CircuitBreakerConfig.custom()
                .slidingWindowSize(10)
                .minimumNumberOfCalls(5)
                .failureRateThreshold(50)
                .waitDurationInOpenState(Duration.ofSeconds(30))
                .build());
    }

    @Test
    void 连续失败过阈值后熔断打开快速失败并计数rejected() {
        MeterRegistry registry = new SimpleMeterRegistry();
        CircuitBreaker cb = breaker();
        MonitoredModel model = new MonitoredModel(failingModel(), cb, "main", registry);

        for (int i = 0; i < 5; i++) {
            assertThatThrownBy(() -> model.stream(List.of(), List.of(), null).blockLast())
                    .hasMessageContaining("relay down");
        }
        assertThat(cb.getState()).isEqualTo(CircuitBreaker.State.OPEN);

        // 打开后不再触碰底层模型：直接以熔断保护信息快速失败
        long start = System.nanoTime();
        assertThatThrownBy(() -> model.stream(List.of(), List.of(), null).blockLast())
                .hasMessageContaining("熔断保护");
        assertThat(System.nanoTime() - start).isLessThan(Duration.ofSeconds(1).toNanos());

        assertThat(registry.get("llm_call_seconds")
                .tag("tier", "main").tag("outcome", "fail").timer().count()).isEqualTo(5);
        assertThat(registry.get("llm_call_seconds")
                .tag("tier", "main").tag("outcome", "rejected").timer().count()).isEqualTo(1);
    }

    @Test
    void 成功完成按ok口径计数() {
        MeterRegistry registry = new SimpleMeterRegistry();
        Model emptyModel = new Model() {
            @Override
            public Flux<ChatResponse> stream(List<Msg> messages, List<ToolSchema> tools,
                                             GenerateOptions options) {
                return Flux.empty();
            }

            @Override
            public String getModelName() {
                return "fake";
            }
        };
        MonitoredModel model = new MonitoredModel(emptyModel, breaker(), "fast", registry);
        model.stream(List.of(), List.of(), null).blockLast();

        assertThat(registry.get("llm_call_seconds")
                .tag("tier", "fast").tag("outcome", "ok").timer().count()).isEqualTo(1);
    }
}
