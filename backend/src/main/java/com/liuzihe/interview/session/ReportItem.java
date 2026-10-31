package com.liuzihe.interview.session;

/**
 * 逐题回顾：题目、候选人回答摘要、点评与该题得分（0 到 10）。
 */
public record ReportItem(String question, String userAnswerSummary, String comment, int score) {

    public ReportItem {
        if (score < 0 || score > 10) {
            throw new IllegalArgumentException("单题得分必须在 0 到 10 之间");
        }
    }
}
