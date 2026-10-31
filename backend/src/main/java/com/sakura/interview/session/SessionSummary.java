package com.sakura.interview.session;

/**
 * 滚动前情摘要：text 为当前摘要全文，coveredSeq 为摘要已覆盖到的最大消息序号。
 * coveredSeq 单调递增，重启后从存储层恢复，不重复摘要。
 */
public record SessionSummary(String text, int coveredSeq) {
}
