package com.liuzihe.interview.rag;

/**
 * 题库单题：题号、题干、答案要点、知识点标签与来源。
 * searchText 供向量化与 BM25 稀疏检索共用同一份文本。
 */
public record BankQuestion(String id, String question, String points, String tags, String source) {

    public String searchText() {
        return question + "\n" + points + "\n" + tags;
    }
}
