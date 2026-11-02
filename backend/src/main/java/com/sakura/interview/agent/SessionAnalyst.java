package com.sakura.interview.agent;

import com.google.gson.Gson;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.sakura.interview.infra.StructuredOutputInvoker;
import com.sakura.interview.session.AnalysisCache;
import com.sakura.interview.tool.JdProfile;

import java.util.ArrayList;
import java.util.List;

/**
 * 创建会话分析器：简历摘要与 JD 画像合并为一次结构化调用——两次模型往返变一次，
 * 建会话耗时从相加变为取较长者。结果按输入哈希缓存，同简历同 JD 重复建会话直接命中。
 * 解析降级沿用旧语义：digest 缺失回退占位文案；skills 缺失时 JD 部分降级为原文整体、
 * 摘要不陪葬；不完整结果不落缓存，重复建会话时重试完整分析而非钉死坏结果。
 */
public final class SessionAnalyst {

    /** 分析结果：简历摘要加岗位画像。 */
    public record Analysis(String resumeDigest, JdProfile jdProfile) {
    }

    private static final Gson GSON = new Gson();

    /** JD 文本长度上限沿用旧解析工具口径。 */
    private static final int MAX_JD_LENGTH = 20 * 1024;

    private final StructuredOutputInvoker invoker;
    private final AnalysisCache cache;

    public SessionAnalyst(StructuredOutputInvoker invoker, AnalysisCache cache) {
        this.invoker = invoker;
        this.cache = cache;
    }

    public Analysis analyze(String resumeText, String jdText) {
        if (jdText == null || jdText.isBlank()) {
            throw new IllegalArgumentException("JD 文本为空");
        }
        if (jdText.length() > MAX_JD_LENGTH) {
            throw new IllegalArgumentException("JD 文本超过 20KB 上限");
        }
        String cacheKey = AnalysisCache.keyOf(resumeText, jdText);
        String cached = cache.load(cacheKey).orElse(null);
        if (cached != null) {
            try {
                return fromJson(GSON.fromJson(cached, JsonObject.class), jdText);
            } catch (RuntimeException e) {
                System.err.printf("[分析缓存] 缓存内容损坏按未命中处理：%s%n", e.getMessage());
            }
        }
        JsonObject obj = invoker.invoke(Prompts.CREATE_ANALYST_SYSTEM,
                "【简历】\n" + (resumeText == null ? "" : resumeText).strip()
                        + "\n\n【岗位 JD】\n" + jdText.strip(),
                JsonObject.class);
        Analysis analysis = fromJson(obj, jdText);
        if (complete(obj)) {
            cache.save(cacheKey, GSON.toJson(obj));
        } else {
            System.err.println("[分析缓存] 分析结果不完整不落缓存，重复建会话将重试完整分析");
        }
        return analysis;
    }

    /** 完整结果才落缓存：digest 与 skills 齐备。 */
    private static boolean complete(JsonObject obj) {
        return obj.has("digest") && !obj.get("digest").getAsString().isBlank()
                && !stringList(obj, "skills").isEmpty();
    }

    /** 宽松解析：digest 缺失回退占位文案；skills 缺失时 JD 部分降级为原文整体。 */
    private static Analysis fromJson(JsonObject obj, String jdText) {
        String digest = obj.has("digest") ? obj.get("digest").getAsString() : "";
        if (digest.isBlank()) {
            digest = "简历解析未产出摘要";
        }
        String direction = obj.has("direction") ? obj.get("direction").getAsString() : "未知方向";
        List<String> skills = stringList(obj, "skills");
        JdProfile jd = skills.isEmpty()
                ? new JdProfile("解析降级", List.of(jdText.strip()), List.of())
                : new JdProfile(direction, skills, stringList(obj, "bonus"));
        return new Analysis(digest, jd);
    }

    private static List<String> stringList(JsonObject obj, String key) {
        if (!obj.has(key) || !obj.get(key).isJsonArray()) {
            return List.of();
        }
        List<String> values = new ArrayList<>();
        for (JsonElement element : obj.getAsJsonArray(key)) {
            String value = element.getAsString() == null ? "" : element.getAsString().strip();
            if (!value.isEmpty()) {
                values.add(value);
            }
        }
        return values;
    }
}
