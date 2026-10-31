package com.sakura.interview.session;

import com.sakura.interview.infra.StructuredOutputInvoker;

import java.util.ArrayList;
import java.util.List;

/**
 * 报告生成器：全量消息配对成 QA，分批结构化评估逐题打分，最后汇总四维分与建议。
 * 竞品吸收：批失败二分恢复（全场额外调用预算上限）；最终失败显式降级 0 分不伪装；
 * 汇总只喂逐题摘要不重放全量。token 总量与时长取自存储层，不经模型。
 */
public final class ReportGenerator {

    static final int EVAL_BATCH = 8;
    static final int BISECT_BUDGET = 4;
    static final int ANSWER_MAX_CHARS = 1200;
    static final int QUESTION_MAX_CHARS = 800;
    static final String DEGRADED_COMMENT = "系统降级：本题评估失败，按 0 分计，不代表真实水平。";

    private final StructuredOutputInvoker invoker;
    private final SessionStore store;

    public ReportGenerator(StructuredOutputInvoker invoker, SessionStore store) {
        this.invoker = invoker;
        this.store = store;
    }

    /** 生成完整报告。会话不存在或无题目时抛出，由 Worker 记失败。 */
    public InterviewReport generate(String sessionId) {
        SessionState state = store.loadSession(sessionId)
                .orElseThrow(() -> new IllegalStateException("会话不存在：" + sessionId));
        List<StoredMessage> messages = store.readAll(sessionId);
        List<QaPair> pairs = pairUp(messages);
        if (pairs.isEmpty()) {
            throw new IllegalStateException("会话没有可评估的问答记录");
        }
        List<ReportItem> items = new ArrayList<>();
        int[] extraCalls = {0};
        for (int i = 0; i < pairs.size(); i += EVAL_BATCH) {
            List<QaPair> batch = pairs.subList(i, Math.min(i + EVAL_BATCH, pairs.size()));
            evaluateBatch(batch, items, extraCalls);
        }
        AggregatedReport aggregated = aggregate(state, pairs.size(), items);
        long tokenTotal = messages.stream()
                .mapToLong(message -> message.inputTokens() + message.outputTokens()).sum();
        return new InterviewReport(sessionId, aggregated.scores(), items,
                aggregated.suggestions(), tokenTotal, store.durationMs(sessionId));
    }

    /** 配对规则：interviewer 为题目，其后连续 user 消息拼为回答；chat 跳过；首题前的 user 不计。 */
    static List<QaPair> pairUp(List<StoredMessage> messages) {
        List<QaPair> pairs = new ArrayList<>();
        String question = null;
        StringBuilder answer = new StringBuilder();
        for (StoredMessage message : messages) {
            switch (message.role()) {
                case "interviewer" -> {
                    if (question != null) {
                        pairs.add(new QaPair(question, answer.toString().strip()));
                    }
                    question = message.text();
                    answer.setLength(0);
                }
                case "user" -> {
                    if (question != null) {
                        if (!answer.isEmpty()) {
                            answer.append('\n');
                        }
                        answer.append(message.text());
                    }
                }
                default -> {
                    // chat 闲聊不构成问答
                }
            }
        }
        if (question != null) {
            pairs.add(new QaPair(question, answer.toString().strip()));
        }
        return pairs;
    }

    private void evaluateBatch(List<QaPair> batch, List<ReportItem> sink, int[] extraCalls) {
        try {
            sink.addAll(invokeBatch(batch));
            return;
        } catch (RuntimeException e) {
            System.err.printf("[报告生成] 批次评估失败进入二分：%s%n", e.getMessage());
        }
        if (batch.size() == 1) {
            sink.add(degradedItem(batch.get(0)));
            return;
        }
        int mid = batch.size() / 2;
        List<List<QaPair>> halves = List.of(
                batch.subList(0, mid), batch.subList(mid, batch.size()));
        for (List<QaPair> half : halves) {
            if (extraCalls[0] < BISECT_BUDGET) {
                extraCalls[0]++;
                try {
                    sink.addAll(invokeBatch(half));
                    continue;
                } catch (RuntimeException e) {
                    System.err.printf("[报告生成] 半批评估失败降级：%s%n", e.getMessage());
                }
            } else {
                System.err.println("[报告生成] 二分预算耗尽，半批整体降级");
            }
            half.forEach(pair -> sink.add(degradedItem(pair)));
        }
    }

    private List<ReportItem> invokeBatch(List<QaPair> batch) {
        StringBuilder user = new StringBuilder();
        user.append("本场面试的 ").append(batch.size()).append(" 道题，逐题输出评价：\n");
        int index = 1;
        for (QaPair pair : batch) {
            user.append("\n【第").append(index++).append("题】\n题目：")
                    .append(compress(pair.question(), QUESTION_MAX_CHARS))
                    .append("\n候选人回答：")
                    .append(compress(pair.answer(), ANSWER_MAX_CHARS)).append('\n');
        }
        BatchEvaluation result = invoker.invoke(ReportPrompts.BATCH_SYSTEM,
                user.toString(), BatchEvaluation.class);
        if (result == null || result.items() == null || result.items().isEmpty()) {
            throw new IllegalStateException("批评估输出为空");
        }
        return result.items();
    }

    private AggregatedReport aggregate(SessionState state, int pairCount, List<ReportItem> items) {
        try {
            AggregatedReport aggregated = invoker.invoke(ReportPrompts.AGGREGATE_SYSTEM,
                    aggregateUser(state, pairCount, items), AggregatedReport.class);
            if (aggregated != null && aggregated.scores() != null && aggregated.suggestions() != null) {
                return aggregated;
            }
            throw new IllegalStateException("汇总输出不完整");
        } catch (RuntimeException e) {
            System.err.printf("[报告生成] 汇总失败降级为批次均值：%s%n", e.getMessage());
            long degraded = items.stream()
                    .filter(item -> DEGRADED_COMMENT.equals(item.comment())).count();
            int average = (int) Math.round(
                    items.stream().mapToInt(ReportItem::score).average().orElse(0));
            List<String> suggestions = new ArrayList<>();
            suggestions.add("系统降级：整体评估调用失败，总分为逐题均值，未经过整体评估，仅供参考。");
            if (degraded > 0) {
                suggestions.add("另有 " + degraded + " 题因评估失败按 0 分计入，拉低了均值。");
            }
            return new AggregatedReport(
                    new ReportScore(average, average, average, average), suggestions);
        }
    }

    private String aggregateUser(SessionState state, int pairCount, List<ReportItem> items) {
        long degraded = items.stream()
                .filter(item -> DEGRADED_COMMENT.equals(item.comment())).count();
        StringBuilder sb = new StringBuilder();
        sb.append("候选人简历摘要：").append(state.resumeDigest()).append('\n');
        sb.append("目标岗位：").append(state.jdProfile().direction()).append('\n');
        sb.append("对话统计：共 ").append(pairCount).append(" 道题");
        if (degraded > 0) {
            sb.append("，其中 ").append(degraded).append(" 题因系统降级按 0 分计");
        }
        sb.append("。\n\n逐题评价：\n");
        items.stream().limit(20).forEach(item -> sb.append("- ")
                .append(truncate(item.question(), 50))
                .append(" | 得分 ").append(item.score())
                .append(" | ").append(truncate(item.comment(), 80)).append('\n'));
        return sb.toString();
    }

    private static ReportItem degradedItem(QaPair pair) {
        return new ReportItem(truncate(pair.question(), 50), "（评估失败，未生成摘要）",
                DEGRADED_COMMENT, 0);
    }

    /** 首尾保留式压缩：保头部约六成加尾部约三成，中间标注已压缩。 */
    private static String compress(String text, int maxChars) {
        String stripped = text == null ? "" : text.strip();
        if (stripped.length() <= maxChars) {
            return stripped;
        }
        int head = maxChars * 6 / 10;
        int tail = maxChars * 3 / 10;
        return stripped.substring(0, head) + "\n……（中间内容已压缩）……\n"
                + stripped.substring(stripped.length() - tail);
    }

    private static String truncate(String text, int maxChars) {
        if (text == null || text.length() <= maxChars) {
            return text;
        }
        return text.substring(0, maxChars) + "…";
    }
}
