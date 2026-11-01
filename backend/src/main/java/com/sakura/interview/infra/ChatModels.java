package com.sakura.interview.infra;

import io.agentscope.core.model.Model;
import io.agentscope.extensions.model.dashscope.DashScopeChatModel;
import io.agentscope.extensions.model.ollama.OllamaChatModel;
import io.agentscope.extensions.model.openai.OpenAIChatModel;

/**
 * 模型客户端统一工厂：按 MODEL_PROVIDER 环境变量分派三路 provider，
 * 档位分两级——main 为面试官与报告的主力档，fast 为摘要路由解析等辅助档
 * （快档未配置时回退主档模型）。密钥来源见 EnvKeys。
 */
public final class ChatModels {

    public static final String TIER_MAIN = "main";
    public static final String TIER_FAST = "fast";

    private ChatModels() {
    }

    public static Model chat() {
        return chat(TIER_MAIN);
    }

    public static Model chat(String tier) {
        return switch (EnvKeys.modelProvider()) {
            case "openai" -> openAi(tier);
            case "ollama" -> ollama(tier);
            default -> dashScope(tier);
        };
    }

    /** 降级档位：主档失败落快档，快档失败落主档。 */
    public static String otherTier(String tier) {
        return TIER_FAST.equals(tier) ? TIER_MAIN : TIER_FAST;
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
