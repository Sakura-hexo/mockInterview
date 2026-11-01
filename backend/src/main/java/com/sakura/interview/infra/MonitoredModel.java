package com.sakura.interview.infra;

import io.agentscope.core.message.Msg;
import io.agentscope.core.model.ChatResponse;
import io.agentscope.core.model.GenerateOptions;
import io.agentscope.core.model.Model;
import io.agentscope.core.model.ToolSchema;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import reactor.core.publisher.Flux;

import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * 模型熔断装饰器：包在 ChatModels 产出的 Model 外，按档位独立熔断——
 * 滑动窗口 10 次最少 5 次调用失败率过半即打开，打开期间快速失败不等模型超时。
 * 同时记录 llm_call_seconds（标签 tier 与 outcome，取值 ok fail rejected cancel）。
 */
final class MonitoredModel implements Model {

    private final Model delegate;
    private final CircuitBreaker breaker;
    private final String tier;
    private final MeterRegistry registry;

    MonitoredModel(Model delegate, CircuitBreaker breaker, String tier, MeterRegistry registry) {
        this.delegate = delegate;
        this.breaker = breaker;
        this.tier = tier;
        this.registry = registry;
    }

    @Override
    public Flux<ChatResponse> stream(List<Msg> messages, List<ToolSchema> tools, GenerateOptions options) {
        long start = System.nanoTime();
        if (!breaker.tryAcquirePermission()) {
            record(start, "rejected");
            return Flux.error(new IllegalStateException("模型服务熔断保护中，请稍后重试"));
        }
        return delegate.stream(messages, tools, options)
                .doOnComplete(() -> success(start))
                .doOnCancel(() -> success(start))
                .doOnError(e -> failure(start, e));
    }

    private void success(long start) {
        breaker.onSuccess(System.nanoTime() - start, TimeUnit.NANOSECONDS);
        record(start, "ok");
    }

    private void failure(long start, Throwable cause) {
        breaker.onError(System.nanoTime() - start, TimeUnit.NANOSECONDS, cause);
        record(start, "fail");
    }

    @Override
    public String getModelName() {
        return delegate.getModelName();
    }

    @Override
    public boolean supportsNativeStructuredOutput() {
        return delegate.supportsNativeStructuredOutput();
    }

    @Override
    public boolean supportsNativeStructuredOutputWithTools() {
        return delegate.supportsNativeStructuredOutputWithTools();
    }

    @Override
    public int getContextWindowSize() {
        return delegate.getContextWindowSize();
    }

    private void record(long startNanos, String outcome) {
        if (registry == null) {
            return;
        }
        Timer.builder("llm_call_seconds")
                .tag("tier", tier)
                .tag("outcome", outcome)
                .register(registry)
                .record(System.nanoTime() - startNanos, TimeUnit.NANOSECONDS);
    }
}
