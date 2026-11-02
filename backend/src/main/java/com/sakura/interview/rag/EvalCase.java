package com.sakura.interview.rag;

import java.util.List;

/**
 * 评测单条：编号、查询、期望题号（空列表表示题库外负例）、形态与备注。
 */
public record EvalCase(String id, String query, List<String> goldIds, String tier, String note) {
}
