package com.liuzihe.interview.rag;

import java.util.List;

/**
 * 检索端口：应用层与工具层经此接口取题。
 * Milvus 是当前实现，单测用 fake 替换，符合端口层可换实现的设计。
 */
public interface QuestionSearcher {

    List<SearchHit> search(String query, int topK);
}
