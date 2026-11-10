package com.sakura.interview.evaluation;

import java.util.List;

/**
 * 参考回答员的结构化输出：按题号回填的可背诵参考回答。
 * index 为批内 1 起的题目序号（与提示词中的【第N题】一致）。
 */
public record AnswerEvaluation(List<AnswerEntry> answers) {

    public record AnswerEntry(int index, String referenceAnswer) {
    }
}
