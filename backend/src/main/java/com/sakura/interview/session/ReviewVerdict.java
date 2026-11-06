package com.sakura.interview.session;

import java.util.List;

/**
 * 审稿员的结构化裁决：整体是否通过与问题题清单。
 * index 为 1 起的题目序号，reason 供日志与回炉依据，不进报告正文。
 */
public record ReviewVerdict(boolean pass, List<FailedItem> failedItems) {

    public record FailedItem(int index, String reason) {
    }
}
