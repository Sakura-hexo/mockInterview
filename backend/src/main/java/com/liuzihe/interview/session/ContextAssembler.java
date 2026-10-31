package com.liuzihe.interview.session;

import java.util.ArrayList;
import java.util.List;

/**
 * 上下文组装器：把前情摘要与最近窗口拼成单轮用户输入。
 * 字符硬预算兜底——输出总长恒不超 MAX_CONTEXT_CHARS；预算不足时从最早的
 * 窗口消息开始丢弃，绝不丢最后一条（当前回答）；仅剩当前回答仍超则截尾部加标记。
 */
public final class ContextAssembler {

    /** 最近窗口消息数，约 10 轮问答原文。 */
    public static final int WINDOW_MESSAGES = 20;
    /** 摘要加窗口的总字符预算。 */
    public static final int MAX_CONTEXT_CHARS = 8000;
    /** 单份摘要上限。 */
    public static final int MAX_SUMMARY_CHARS = 2500;

    private static final String TRUNCATED_MARK = "…（内容过长已截断）";
    private static final String SUMMARY_TRUNCATED_MARK = "…（摘要已截断）";

    private ContextAssembler() {
    }

    public static String assemble(List<StoredMessage> window, String summaryText) {
        if (window == null || window.isEmpty()) {
            return "";
        }
        String summary = capSummary(summaryText);
        String header = summary.isEmpty() ? "" : "【前情摘要】\n" + summary + "\n\n";
        int reserve = header.length() + "【最近对话】\n\n【候选人本轮回答】\n".length();
        int budget = Math.max(0, MAX_CONTEXT_CHARS - reserve);

        List<StoredMessage> kept = new ArrayList<>();
        int used = 0;
        for (int i = window.size() - 1; i >= 0; i--) {
            int cost = formatLine(window.get(i)).length() + 1;
            if (!kept.isEmpty() && used + cost > budget) {
                break;
            }
            kept.add(0, window.get(i));
            used += cost;
            if (used >= budget) {
                break;
            }
        }

        StringBuilder sb = new StringBuilder(MAX_CONTEXT_CHARS + 64);
        sb.append(header);
        StoredMessage current = kept.remove(kept.size() - 1);
        if (!kept.isEmpty()) {
            sb.append("【最近对话】\n");
            for (StoredMessage message : kept) {
                sb.append(formatLine(message)).append('\n');
            }
            sb.append('\n');
        }
        sb.append("【候选人本轮回答】\n");
        sb.append(truncateTail(current.text(), MAX_CONTEXT_CHARS - sb.length()));
        return sb.toString();
    }

    private static String formatLine(StoredMessage message) {
        String speaker = "user".equals(message.role()) ? "候选人" : "面试官";
        return speaker + "：" + message.text();
    }

    private static String capSummary(String summaryText) {
        if (summaryText == null) {
            return "";
        }
        String summary = summaryText.strip();
        if (summary.length() > MAX_SUMMARY_CHARS) {
            summary = summary.substring(0, MAX_SUMMARY_CHARS) + SUMMARY_TRUNCATED_MARK;
        }
        return summary;
    }

    private static String truncateTail(String text, int maxChars) {
        if (text.length() <= maxChars) {
            return text;
        }
        if (maxChars <= TRUNCATED_MARK.length()) {
            return text.substring(0, Math.max(0, maxChars));
        }
        return text.substring(0, maxChars - TRUNCATED_MARK.length()) + TRUNCATED_MARK;
    }
}
