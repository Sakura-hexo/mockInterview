package com.sakura.interview.infra;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;

/**
 * 环境与密钥加载：优先读进程环境变量，其次读 .env 文件（当前目录或其上级目录）。
 * .env 不进仓库，真实密钥只存在于本地，代码与配置零明文。
 */
public final class EnvKeys {

    private static volatile Map<String, String> cache;

    private EnvKeys() {
    }

    public static String dashScopeApiKey() {
        String key = load().get("DASHSCOPE_API_KEY");
        if (key == null || key.isBlank()) {
            throw new IllegalStateException(
                    "未找到 DASHSCOPE_API_KEY：请设置环境变量，"
                            + "或复制 backend/.env.example 为 backend/.env 并填入真实密钥");
        }
        return key.trim();
    }

    public static String dashScopeModel() {
        return load().getOrDefault("DASHSCOPE_MODEL", "qwen-plus");
    }

    /** 快档模型：未配置时回退主档模型，保持单模型环境天然兼容。 */
    public static String dashScopeModelFast() {
        return orDefault("DASHSCOPE_MODEL_FAST", dashScopeModel());
    }

    public static String modelProvider() {
        return orDefault("MODEL_PROVIDER", "dashscope").toLowerCase();
    }

    /**
     * embedding 独立开关（env EMBEDDING_PROVIDER）：与聊天分离，
     * 供聊天与向量检索走不同厂商的混搭场景（如聊天 DeepSeek、embedding 百炼）。
     * 未配置时回落 MODEL_PROVIDER，行为与共用开关的现状完全一致。
     */
    public static String embeddingProvider() {
        return orDefault("EMBEDDING_PROVIDER", modelProvider());
    }

    /**
     * 管理员用户名（env ADMIN_USERNAME）：指定谁可见管理页签（全站统计与题库上传）。
     * 不配置时管理接口一律 404 零暴露；不建角色表，单人有明确管理员即可。
     */
    public static String adminUsername() {
        return orDefault("ADMIN_USERNAME", "").trim();
    }

    /** 内置管理员密码（env ADMIN_PASSWORD）：默认 admin，公开部署前改强密码重启即重置生效。 */
    public static String adminPassword() {
        return orDefault("ADMIN_PASSWORD", "admin");
    }

    /** 服务端 TTS 通道（env TTS_PROVIDER）：off 关闭（前端回落浏览器朗读），edge 为免费微软朗读通道。 */
    public static String ttsProvider() {
        return orDefault("TTS_PROVIDER", "off").toLowerCase();
    }

    /** TTS 默认嗓音（env TTS_VOICE）：微软神经嗓音名，默认晓晓。 */
    public static String ttsVoice() {
        return orDefault("TTS_VOICE", "zh-CN-XiaoxiaoNeural");
    }

    /** edge-tts 边车地址（env TTS_EDGE_URL）：容器编排指服务名，本机默认 8000 端口。 */
    public static String ttsEdgeUrl() {
        return orDefault("TTS_EDGE_URL", "http://localhost:8000");
    }

    /** 服务端 ASR 通道（env ASR_PROVIDER）：off 关闭（前端回落浏览器识别），sherpa 为本地 paraformer 识别。 */
    public static String asrProvider() {
        return orDefault("ASR_PROVIDER", "off").toLowerCase();
    }

    /** asr 边车地址（env ASR_URL）：容器编排指服务名，本机默认 8001 端口。 */
    public static String asrUrl() {
        return orDefault("ASR_URL", "http://localhost:8001");
    }

    /**
     * 面试官思考流分档（env LLM_THINKING 三值）：false 全关、true 全开、
     * auto 按难度分档——MEDIUM、HARD 开，EASY 关。默认 false 兼容现有部署。
     * 思考流不进正文但按解码速度逐 token 生成，轮次付出数十秒死等，只换深度场景值得。
     * 只对面试官角色生效，报告、压缩、路由、闲聊等其余角色不经此开关（一律关闭）。
     */
    public static boolean llmThinkingFor(String difficulty) {
        return thinkingResolves(orDefault("LLM_THINKING", "false"), difficulty);
    }

    /** 三值解析（包内可见供单测直测）：mode 与 difficulty 均宽容大小写与空白。 */
    static boolean thinkingResolves(String mode, String difficulty) {
        String normalized = mode == null ? "" : mode.trim().toLowerCase();
        return switch (normalized) {
            case "true" -> true;
            case "auto" -> "MEDIUM".equalsIgnoreCase(difficulty) || "HARD".equalsIgnoreCase(difficulty);
            default -> false;
        };
    }

    public static String openAiApiKey() {
        String key = load().get("OPENAI_API_KEY");
        if (key == null || key.isBlank()) {
            throw new IllegalStateException(
                    "未找到 OPENAI_API_KEY：MODEL_PROVIDER=openai 时必须提供，"
                            + "请在 backend/.env 填入所选 OpenAI 兼容平台的密钥");
        }
        return key.trim();
    }

    public static String openAiBaseUrl() {
        return orDefault("OPENAI_BASE_URL", "https://open.bigmodel.cn/api/paas/v4");
    }

    /**
     * embedding 独立端点与密钥（EMBEDDING_BASE_URL / EMBEDDING_API_KEY）：
     * 与聊天分线时的覆盖项，如聊天 DeepSeek、向量检索走中转站的 text-embedding-v3；
     * 未配置时回落聊天线的 OPENAI_*，行为与共用现状一致。
     */
    public static String embeddingBaseUrl() {
        return orDefault("EMBEDDING_BASE_URL", openAiBaseUrl());
    }

    public static String embeddingApiKey() {
        return orDefault("EMBEDDING_API_KEY", openAiApiKey());
    }

    public static String openAiModel() {
        return orDefault("OPENAI_MODEL", "glm-4-flash");
    }

    /** 快档模型：未配置时回退主档模型，保持单模型环境天然兼容。 */
    public static String openAiModelFast() {
        return orDefault("OPENAI_MODEL_FAST", openAiModel());
    }

    public static String ollamaBaseUrl() {
        return orDefault("OLLAMA_BASE_URL", "http://localhost:11434");
    }

    public static String ollamaModel() {
        return orDefault("OLLAMA_MODEL", "qwen2.5:7b");
    }

    /** 快档模型：未配置时回退主档模型。 */
    public static String ollamaModelFast() {
        return orDefault("OLLAMA_MODEL_FAST", ollamaModel());
    }

    public static String openAiEmbeddingModel() {
        return orDefault("OPENAI_EMBEDDING_MODEL", "text-embedding-v3");
    }

    public static String dashScopeEmbeddingModel() {
        return orDefault("DASHSCOPE_EMBEDDING_MODEL", "text-embedding-v3");
    }

    public static String ollamaEmbeddingModel() {
        return orDefault("OLLAMA_EMBEDDING_MODEL", "nomic-embed-text");
    }

    public static String milvusUri() {
        return orDefault("MILVUS_URI", "http://localhost:19530");
    }

    public static String milvusCollection() {
        return orDefault("MILVUS_COLLECTION", "question_bank");
    }

    public static String mysqlUrl() {
        return orDefault("MYSQL_URL",
                "jdbc:mysql://localhost:3306/interview?createDatabaseIfNotExist=true"
                        + "&useUnicode=true&characterEncoding=utf8&serverTimezone=Asia/Shanghai");
    }

    /** 连接池大小：50 并发压测定位出 3 连接排队瓶颈后默认上调（压测记录 2026-11-02）。 */
    public static int mysqlPoolSize() {
        String value = load().get("MYSQL_POOL");
        if (value == null || value.isBlank()) {
            return 10;
        }
        try {
            return Math.max(1, Integer.parseInt(value.trim()));
        } catch (NumberFormatException e) {
            return 10;
        }
    }

    public static String mysqlUsername() {
        return orDefault("MYSQL_USERNAME", "root");
    }

    public static String mysqlPassword() {
        String password = load().get("MYSQL_PASSWORD");
        if (password == null || password.isBlank()) {
            throw new IllegalStateException(
                    "未找到 MYSQL_PASSWORD：请在 backend/.env 填入本机 MySQL 的密码"
                            + "（MYSQL_USERNAME 默认 root，容器 mysql 时改 MYSQL_URL 端口）");
        }
        return password.trim();
    }

    public static String redisUri() {
        return orDefault("REDIS_URI", "redis://localhost:6379");
    }

    private static String orDefault(String key, String fallback) {
        String value = load().get(key);
        return value == null || value.isBlank() ? fallback : value.trim();
    }

    private static Map<String, String> load() {
        if (cache == null) {
            synchronized (EnvKeys.class) {
                if (cache == null) {
                    cache = mergeEnvAndDotEnv();
                }
            }
        }
        return cache;
    }

    private static Map<String, String> mergeEnvAndDotEnv() {
        Map<String, String> merged = new HashMap<>();
        Path envFile = resolveEnvFile();
        if (envFile != null) {
            readDotEnv(envFile, merged);
        }
        System.getenv().forEach(merged::put);
        return Map.copyOf(merged);
    }

    private static void readDotEnv(Path envFile, Map<String, String> target) {
        try {
            for (String line : Files.readAllLines(envFile)) {
                String trimmed = line.trim();
                if (trimmed.isEmpty() || trimmed.startsWith("#")) {
                    continue;
                }
                int eq = trimmed.indexOf('=');
                if (eq <= 0) {
                    continue;
                }
                target.put(trimmed.substring(0, eq).trim(), unwrap(trimmed.substring(eq + 1).trim()));
            }
        } catch (IOException e) {
            throw new IllegalStateException("读取 .env 失败：" + envFile, e);
        }
    }

    private static String unwrap(String value) {
        if (value.length() >= 2 && value.startsWith("\"") && value.endsWith("\"")) {
            return value.substring(1, value.length() - 1);
        }
        return value;
    }

    private static Path resolveEnvFile() {
        Path cwd = Path.of("").toAbsolutePath();
        Path candidate = cwd.resolve(".env");
        if (Files.isRegularFile(candidate)) {
            return candidate;
        }
        Path parent = cwd.getParent();
        if (parent != null) {
            Path parentCandidate = parent.resolve(".env");
            if (Files.isRegularFile(parentCandidate)) {
                return parentCandidate;
            }
        }
        return null;
    }
}
