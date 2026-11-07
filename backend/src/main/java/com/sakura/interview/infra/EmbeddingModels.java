package com.sakura.interview.infra;

import io.agentscope.core.embedding.EmbeddingModel;
import io.agentscope.core.embedding.dashscope.DashScopeTextEmbedding;
import io.agentscope.core.embedding.ollama.OllamaTextEmbedding;
import io.agentscope.core.embedding.openai.OpenAITextEmbedding;

/**
 * Embedding 客户端统一工厂：按 EMBEDDING_PROVIDER 独立分派三路（缺省回落 MODEL_PROVIDER）。
 * 聊天与向量检索可走不同厂商（如聊天 DeepSeek、embedding 百炼 text-embedding-v3）。
 * 维度随模型走：text-embedding-v3 为 1024，本地 nomic-embed-text 为 768；
 * 切换 embedding 模型后 Milvus 集合维度会变，必须用 --rebuild 重建题库。
 */
public final class EmbeddingModels {

    private static final int TEXT_EMBEDDING_V3_DIM = 1024;
    private static final int NOMIC_EMBED_DIM = 768;

    private EmbeddingModels() {
    }

    public static EmbeddingModel embedding() {
        return switch (EnvKeys.embeddingProvider()) {
            case "openai" -> OpenAITextEmbedding.builder()
                    .apiKey(EnvKeys.embeddingApiKey())
                    .baseUrl(EnvKeys.embeddingBaseUrl())
                    .modelName(EnvKeys.openAiEmbeddingModel())
                    .dimensions(TEXT_EMBEDDING_V3_DIM)
                    .build();
            case "ollama" -> OllamaTextEmbedding.builder()
                    .baseUrl(EnvKeys.ollamaBaseUrl())
                    .modelName(EnvKeys.ollamaEmbeddingModel())
                    .dimensions(NOMIC_EMBED_DIM)
                    .build();
            default -> DashScopeTextEmbedding.builder()
                    .apiKey(EnvKeys.dashScopeApiKey())
                    .modelName(EnvKeys.dashScopeEmbeddingModel())
                    .dimensions(TEXT_EMBEDDING_V3_DIM)
                    .build();
        };
    }
}
