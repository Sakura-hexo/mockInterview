package com.sakura.interview.infra;

import io.agentscope.core.model.Model;
import io.agentscope.extensions.model.dashscope.DashScopeChatModel;
import io.agentscope.extensions.model.ollama.OllamaChatModel;
import io.agentscope.extensions.model.openai.OpenAIChatModel;

/**
 * 模型客户端统一工厂：按 MODEL_PROVIDER 环境变量分派三路 provider。
 * 不填默认 dashscope（正式评测口径）；openai 通吃智谱等 OpenAI 兼容端点；
 * ollama 为本地零成本冒烟通道。密钥来源见 EnvKeys。
 */
public final class ChatModels {

    private ChatModels() {
    }

    public static Model chat() {
        return switch (EnvKeys.modelProvider()) {
            case "openai" -> openAi();
            case "ollama" -> ollama();
            default -> dashScope();
        };
    }

    private static Model dashScope() {
        return DashScopeChatModel.builder()
                .apiKey(EnvKeys.dashScopeApiKey())
                .modelName(EnvKeys.dashScopeModel())
                .build();
    }

    private static Model openAi() {
        return OpenAIChatModel.builder()
                .apiKey(EnvKeys.openAiApiKey())
                .baseUrl(EnvKeys.openAiBaseUrl())
                .modelName(EnvKeys.openAiModel())
                .build();
    }

    private static Model ollama() {
        return OllamaChatModel.builder()
                .baseUrl(EnvKeys.ollamaBaseUrl())
                .modelName(EnvKeys.ollamaModel())
                .build();
    }
}
