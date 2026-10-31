package com.sakura.interview.session;

/**
 * 复盘评分，各维度 0 到 10 分。
 */
public record ReportScore(int overall, int technicalDepth, int expression, int followUpHandling) {

    public ReportScore {
        if (overall < 0 || overall > 10 || technicalDepth < 0 || technicalDepth > 10
                || expression < 0 || expression > 10 || followUpHandling < 0 || followUpHandling > 10) {
            throw new IllegalArgumentException("评分必须在 0 到 10 之间");
        }
    }
}
