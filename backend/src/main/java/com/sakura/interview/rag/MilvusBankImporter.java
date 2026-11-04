package com.sakura.interview.rag;

import com.sakura.interview.api.QuestionBankImportPort;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * 题库导入的生产实现：复用 CLI 导入的同一套链路——QuestionBankParser 解析、
 * 文内题号查重、ensureCollection 幂等建集合、importQuestions 按 ID 跳过已存在。
 */
public final class MilvusBankImporter implements QuestionBankImportPort {

    private final MilvusQuestionStore store;

    public MilvusBankImporter(MilvusQuestionStore store) {
        this.store = store;
    }

    @Override
    public ImportResult importText(String content) {
        List<BankQuestion> questions;
        try {
            questions = QuestionBankParser.parseText(content);
        } catch (IllegalStateException e) {
            throw new IllegalArgumentException(e.getMessage(), e);
        }
        Set<String> ids = new HashSet<>();
        for (BankQuestion question : questions) {
            if (!ids.add(question.id())) {
                throw new IllegalArgumentException("题号重复：" + question.id());
            }
        }
        store.ensureCollection();
        int imported = store.importQuestions(questions);
        return new ImportResult(questions.size(), imported);
    }
}
