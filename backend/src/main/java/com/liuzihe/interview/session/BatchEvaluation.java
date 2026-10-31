package com.liuzihe.interview.session;

import java.util.List;

/**
 * 批评估的结构化输出包装：List 须用 Class 承载才能走 StructuredOutputInvoker。
 */
public record BatchEvaluation(List<ReportItem> items) {
}
