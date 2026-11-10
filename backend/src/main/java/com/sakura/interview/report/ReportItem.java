package com.sakura.interview.report;

/**
 * 逐题回顾：题目、候选人回答摘要、点评、该题得分（0 到 10）与参考回答。
 * referenceAnswer 由参考回答员按点评指出的缺口定制，评估失败或未生成时为 null。
 */
public record ReportItem(String question, String userAnswerSummary, String comment, int score,
                         String referenceAnswer) {

    public ReportItem {
        if (score < 0 || score > 10) {
            throw new IllegalArgumentException("单题得分必须在 0 到 10 之间");
        }
    }

    /** 兼容无参考回答的构造：评分、降级与存量测试口径。 */
    public ReportItem(String question, String userAnswerSummary, String comment, int score) {
        this(question, userAnswerSummary, comment, score, null);
    }
}
