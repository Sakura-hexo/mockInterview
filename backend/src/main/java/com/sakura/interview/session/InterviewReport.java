package com.sakura.interview.session;

import java.util.List;

/**
 * 面试复盘报告：会话标识、四维评分、逐题回顾、改进建议、token 总量与面试时长。
 */
public record InterviewReport(String sessionId, ReportScore scores, List<ReportItem> items,
                              List<String> suggestions, long tokenTotal, long durationMs) {
}
