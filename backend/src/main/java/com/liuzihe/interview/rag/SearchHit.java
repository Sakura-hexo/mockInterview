package com.liuzihe.interview.rag;

/**
 * 检索命中：题号、题干、标签、来源与融合分数。
 */
public record SearchHit(String id, String question, String tags, String source, float score) {
}
