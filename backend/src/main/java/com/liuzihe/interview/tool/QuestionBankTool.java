package com.liuzihe.interview.tool;

import com.liuzihe.interview.rag.QuestionSearcher;
import com.liuzihe.interview.rag.SearchHit;
import com.liuzihe.interview.rag.SearchTiers;

import java.util.List;

/**
 * 题库检索工具：按查询长度分档取题，输出给面试官用的题目文本块。
 * 依赖注入 QuestionSearcher 端口，单测 fake 替换不连 Milvus；
 * 步骤 4 加 AgentScope 工具注解后挂载到面试官智能体。
 */
public final class QuestionBankTool {

    private final QuestionSearcher searcher;

    public QuestionBankTool(QuestionSearcher searcher) {
        this.searcher = searcher;
    }

    public List<SearchHit> retrieve(String query) {
        return searcher.search(query, SearchTiers.topK(query));
    }

    /** 给面试官智能体用的文本块形态。 */
    public String retrieveAsText(String query) {
        List<SearchHit> hits = retrieve(query);
        if (hits.isEmpty()) {
            return "题库无命中，请基于岗位方向自行出一道相关题目。";
        }
        StringBuilder sb = new StringBuilder();
        for (SearchHit hit : hits) {
            sb.append(hit.id()).append(" ").append(hit.question())
                    .append("（标签：").append(hit.tags()).append("）\n");
        }
        return sb.toString().strip();
    }
}
