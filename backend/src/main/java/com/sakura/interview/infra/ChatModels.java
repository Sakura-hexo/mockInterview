package com.sakura.interview.infra;

import io.agentscope.core.model.Model;
import io.agentscope.extensions.model.dashscope.DashScopeChatModel;
import io.agentscope.extensions.model.ollama.OllamaChatModel;
import io.agentscope.extensions.model.openai.OpenAIChatModel;
import io.github.resilience4j.circuitbreaker.CircuitBreakerConfig;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import io.micrometer.core.instrument.MeterRegistry;

import java.time.Duration;

/**
 * 模型客户端统一工厂：按 MODEL_PROVIDER 环境变量分派三路 provider，
 * 档位分两级——main 为面试官与报告的主力档，fast 为摘要路由解析等辅助档
 * （快档未配置时回退主档模型）。全部产出经熔断装饰器包裹，按档位独立熔断。
 * 密钥来源见 EnvKeys。
 */
public final class ChatModels {

    public static final String TIER_MAIN = "main";
    public static final String TIER_FAST = "fast";

    /** 按档位独立的熔断器：窗口 10 次最少 5 次失败率过半打开，半开等待 30 秒。 */
    private static final CircuitBreakerRegistry BREAKERS = CircuitBreakerRegistry.of(
            CircuitBreakerConfig.custom()
                    .slidingWindowSize(10)
                    .minimumNumberOfCalls(5)
                    .failureRateThreshold(50)
                    .waitDurationInOpenState(Duration.ofSeconds(30))
                    .build());

    private static volatile MeterRegistry meterRegistry;

    private ChatModels() {
    }

    public static Model chat() {
        return chat(TIER_MAIN);
    }

    public static Model chat(String tier) {
        Model raw = switch (EnvKeys.modelProvider()) {
            case "openai" -> openAi(tier);
            case "ollama" -> ollama(tier);
            default -> dashScope(tier);
        };
        return new MonitoredModel(raw, BREAKERS.circuitBreaker("llm-" + tier), tier, meterRegistry);
    }

    /** 降级档位：主档失败落快档，快档失败落主档。 */
    public static String otherTier(String tier) {
        return TIER_FAST.equals(tier) ? TIER_MAIN : TIER_FAST;
    }

    /** 装配层在启动时把业务注册表接到模型熔断与调用计时上。 */
    public static void bindMeterRegistry(MeterRegistry registry) {
        meterRegistry = registry;
    }

    /** 供装配层把熔断器状态指标挂到注册表。 */
    public static CircuitBreakerRegistry circuitBreakerRegistry() {
        return BREAKERS;
    }

    private static Model dashScope(String tier) {
        return DashScopeChatModel.builder()
                .apiKey(EnvKeys.dashScopeApiKey())
                .modelName(modelName(tier, EnvKeys.dashScopeModel(), EnvKeys.dashScopeModelFast()))
                .build();
    }

    private static Model openAi(String tier) {
        return OpenAIChatModel.builder()
                .apiKey(EnvKeys.openAiApiKey())
                .baseUrl(EnvKeys.openAiBaseUrl())
                .modelName(modelName(tier, EnvKeys.openAiModel(), EnvKeys.openAiModelFast()))
                .build();
    }

    private static Model ollama(String tier) {
        return OllamaChatModel.builder()
                .baseUrl(EnvKeys.ollamaBaseUrl())
                .modelName(modelName(tier, EnvKeys.ollamaModel(), EnvKeys.ollamaModelFast()))
                .build();
    }

    private static String modelName(String tier, String main, String fast) {
        return TIER_FAST.equals(tier) ? fast : main;
    }
}
