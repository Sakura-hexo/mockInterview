package com.sakura.interview.tool;

import com.sakura.interview.session.InterviewReport;
import com.sakura.interview.session.ReportItem;

/**
 * 复盘报告导出工具：InterviewReport 结构转 Markdown 字符串，
 * 分评分表、逐题回顾、改进建议三段。
 */
public final class ReportExportTool {

    public String export(InterviewReport report) {
        if (report == null || report.scores() == null || report.items() == null || report.suggestions() == null) {
            throw new IllegalArgumentException("报告或其字段为空");
        }
        StringBuilder sb = new StringBuilder();
        sb.append("# 面试复盘报告\n\n");
        sb.append("会话：").append(report.sessionId())
                .append(" | 总 token：").append(report.tokenTotal())
                .append(" | 时长：").append(report.durationMs()).append("ms\n\n");

        sb.append("## 评分\n\n| 维度 | 得分 |\n|---|---|\n");
        sb.append("| 总体 | ").append(report.scores().overall()).append("/10 |\n");
        sb.append("| 技术深度 | ").append(report.scores().technicalDepth()).append("/10 |\n");
        sb.append("| 表达能力 | ").append(report.scores().expression()).append("/10 |\n");
        sb.append("| 追问应对 | ").append(report.scores().followUpHandling()).append("/10 |\n\n");

        sb.append("## 逐题回顾\n\n");
        if (report.items().isEmpty()) {
            sb.append("（本轮无题目记录）\n\n");
        }
        int index = 1;
        for (ReportItem item : report.items()) {
            sb.append("### ").append(index++).append(". ").append(item.question()).append("\n");
            sb.append("- 回答摘要：").append(item.userAnswerSummary()).append("\n");
            sb.append("- 点评：").append(item.comment()).append("\n");
            sb.append("- 得分：").append(item.score()).append("/10\n\n");
        }

        sb.append("## 改进建议\n\n");
        if (report.suggestions().isEmpty()) {
            sb.append("- 无\n");
        }
        for (String suggestion : report.suggestions()) {
            sb.append("- ").append(suggestion).append("\n");
        }
        return sb.toString().strip();
    }
}
