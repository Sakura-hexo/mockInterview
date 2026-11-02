package com.sakura.interview.session;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Optional;

/**
 * 创建会话分析缓存端口：简历摘要与岗位画像是确定性提取，同输入必同输出，
 * 按输入文本哈希复用，重复建会话不再调模型。难度与题数等配置不进键——
 * 分析输出只由两段文本决定，配置只影响面试官提示词。
 */
public interface AnalysisCache {

    /** 空实现：CLI 与单测用，永不命中也永不写入。 */
    AnalysisCache NONE = new AnalysisCache() {
        @Override
        public Optional<String> load(String cacheKey) {
            return Optional.empty();
        }

        @Override
        public void save(String cacheKey, String json) {
        }
    };

    Optional<String> load(String cacheKey);

    void save(String cacheKey, String json);

    /** 缓存键：两段文本各 strip 后以正文中不会出现的分隔符拼接，取 SHA-256 十六进制。 */
    static String keyOf(String resumeText, String jdText) {
        String joined = (resumeText == null ? "" : resumeText).strip()
                + " ||| " + (jdText == null ? "" : jdText).strip();
        try {
            byte[] hash = MessageDigest.getInstance("SHA-256")
                    .digest(joined.getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder(hash.length * 2);
            for (byte b : hash) {
                hex.append(Character.forDigit((b >> 4) & 0xF, 16))
                        .append(Character.forDigit(b & 0xF, 16));
            }
            return hex.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 摘要算法不可用", e);
        }
    }
}
