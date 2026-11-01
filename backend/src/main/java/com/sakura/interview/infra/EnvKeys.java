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
