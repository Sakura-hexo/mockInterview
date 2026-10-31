package com.liuzihe.interview.session;

import java.util.List;

/**
 * 汇总评估的结构化输出包装：四维评分与改进建议。
 */
public record AggregatedReport(ReportScore scores, List<String> suggestions) {
}
