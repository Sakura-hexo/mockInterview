package com.sakura.interview.report;

import com.sakura.interview.evaluation.AnswerEvaluation;
import com.sakura.interview.evaluation.BatchEvaluation;
import com.sakura.interview.evaluation.QaPair;
import com.sakura.interview.infra.StructuredOutputInvoker;
import com.sakura.interview.session.*;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 报告生成器：全量消息配对成 QA 后由四个智能体角色协作产出报告——
 * 评分员分批逐题打分（批次虚拟线程并行）、审稿员结构化质检并驱动失败题局部回炉
 * （成功题原样保留、回炉耗尽降级标注）、参考回答员按点评缺口定制范文、汇总员聚合四维分与建议。
 * 竞品吸收：批失败二分恢复（全场额外调用预算上限，并行批次下经 CAS 认领）；
 * 最终失败显式降级 0 分不伪装；汇总只喂逐题摘要不重放全量。
 * 审稿与参考回答环节失败不阻断报告：审稿失败跳过质检，范文失败留空。
 * token 总量与时长取自存储层，不经模型。
 */
public final class ReportGenerator {

    static final int EVAL_BATCH = 8;
    static final int BISECT_BUDGET = 4;
    static final int ANSWER_MAX_CHARS = 1200;
    static final int QUESTION_MAX_CHARS = 800;
    static final String DEGRADED_COMMENT = "系统降级：本题评估失败，按 0 分计，不代表真实水平。";
    /** 审稿不通过时的局部回炉轮数上限。 */
    static final int MAX_REWORKS = 2;
    /** 回炉耗尽后追加的存疑标注。 */
    static final String DISPUTED_SUFFIX = "（该题评分经复核存疑）";
    /** 参考回答入库长度保险丝。 */
    static final int ANSWER_STORE_MAX_CHARS = 500;

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
        List<ReportItem> items = scoreInParallel(pairs);
        reviewLoop(pairs, items);
        fillReferenceAnswers(pairs, items);
        AggregatedReport aggregated = aggregate(state, pairs.size(), items);
        long tokenTotal = messages.stream()
                .mapToLong(message -> message.inputTokens() + message.outputTokens()).sum();
        return new InterviewReport(sessionId, aggregated.scores(), items,
                aggregated.suggestions(), tokenTotal, store.durationMs(sessionId));
    }

    /**
     * 配对规则：interviewer 为题目，其后连续 user 消息拼为回答；chat 跳过；首题前的 user 不计。
     * 空回答的配对丢弃——问满前的提前告别语、候选人沉默直接结束等场景下，
     * 无作答的"题目"不构成有效考察，评分 0 分是噪音（2026-11-04 真机抓到告别语计成 0.0/10 题）。
     */
    public static List<QaPair> pairUp(List<StoredMessage> messages) {
        List<QaPair> pairs = new ArrayList<>();
        String question = null;
        StringBuilder answer = new StringBuilder();
        for (StoredMessage message : messages) {
            switch (message.role()) {
                case "interviewer" -> {
                    if (question != null && !answer.isEmpty()) {
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
        if (question != null && !answer.isEmpty()) {
            pairs.add(new QaPair(question, answer.toString().strip()));
        }
        return pairs;
    }

    /** 评分阶段：批次经虚拟线程并行评估，二分预算 CAS 全局共享，结果按题目顺序合并。 */
    private List<ReportItem> scoreInParallel(List<QaPair> pairs) {
        AtomicInteger bisectBudget = new AtomicInteger();
        List<CompletableFuture<List<ReportItem>>> futures = new ArrayList<>();
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            for (int i = 0; i < pairs.size(); i += EVAL_BATCH) {
                List<QaPair> batch = pairs.subList(i, Math.min(i + EVAL_BATCH, pairs.size()));
                futures.add(CompletableFuture.supplyAsync(
                        () -> evaluateBatch(batch, bisectBudget), executor));
            }
            List<ReportItem> items = new ArrayList<>();
            for (CompletableFuture<List<ReportItem>> future : futures) {
                items.addAll(future.join());
            }
            return items;
        }
    }

    private List<ReportItem> evaluateBatch(List<QaPair> batch, AtomicInteger bisectBudget) {
        try {
            return invokeBatch(batch);
        } catch (RuntimeException e) {
            System.err.printf("[报告生成] 批评估失败进入二分：%s%n", e.getMessage());
        }
        if (batch.size() == 1) {
            return List.of(degradedItem(batch.get(0)));
        }
        int mid = batch.size() / 2;
        List<List<QaPair>> halves = List.of(
                batch.subList(0, mid), batch.subList(mid, batch.size()));
        List<ReportItem> items = new ArrayList<>();
        for (List<QaPair> half : halves) {
            if (claimBudget(bisectBudget)) {
                try {
                    items.addAll(invokeBatch(half));
                    continue;
                } catch (RuntimeException e) {
                    System.err.printf("[报告生成] 半批评估失败降级：%s%n", e.getMessage());
                }
            } else {
                System.err.println("[报告生成] 二分预算耗尽，半批整体降级");
            }
            half.forEach(pair -> items.add(degradedItem(pair)));
        }
        return items;
    }

    /** 二分预算的原子认领：并行批次下保证全局消耗恰好不超上限。 */
    private static boolean claimBudget(AtomicInteger budget) {
        while (true) {
            int current = budget.get();
            if (current >= BISECT_BUDGET) {
                return false;
            }
            if (budget.compareAndSet(current, current + 1)) {
                return true;
            }
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
        if (result.items().size() != batch.size()) {
            throw new IllegalStateException("批评估输出题数不符：期望 "
                    + batch.size() + " 实得 " + result.items().size());
        }
        return result.items();
    }

    /**
     * 审稿回炉闭环：审稿员裁决驱动失败题局部重评（成功题原样保留），最多 MAX_REWORKS 轮；
     * 仍不通过则给余下失败题追加存疑标注放行。审稿环节自身失败时跳过质检，不阻断报告。
     */
    private void reviewLoop(List<QaPair> pairs, List<ReportItem> items) {
        ReviewVerdict verdict = review(items);
        for (int rework = 0; verdict != null && !verdict.pass() && rework < MAX_REWORKS; rework++) {
            List<Integer> failed = validIndices(verdict, items.size());
            if (failed.isEmpty()) {
                return;
            }
            List<QaPair> failedPairs = failed.stream()
                    .map(index -> pairs.get(index - 1)).toList();
            List<ReportItem> reEvaluated;
            try {
                reEvaluated = invokeBatch(failedPairs);
            } catch (RuntimeException e) {
                System.err.printf("[报告生成] 回炉重评失败，保留原评分：%s%n", e.getMessage());
                return;
            }
            for (int i = 0; i < failed.size(); i++) {
                items.set(failed.get(i) - 1, reEvaluated.get(i));
            }
            System.err.printf("[报告生成] 审稿不通过，已局部回炉 %d 题%n", failed.size());
            verdict = review(items);
        }
        if (verdict != null && !verdict.pass()) {
            List<Integer> disputed = validIndices(verdict, items.size());
            for (Integer index : disputed) {
                ReportItem item = items.get(index - 1);
                items.set(index - 1, withComment(item, item.comment() + DISPUTED_SUFFIX));
            }
            System.err.printf("[报告生成] 回炉耗尽，%d 题降级标注存疑%n", disputed.size());
        }
    }

    private ReviewVerdict review(List<ReportItem> items) {
        try {
            return invoker.invoke(ReportPrompts.REVIEWER_SYSTEM,
                    reviewUser(items), ReviewVerdict.class);
        } catch (RuntimeException e) {
            System.err.printf("[报告生成] 审稿调用失败，跳过本轮质检：%s%n", e.getMessage());
            return null;
        }
    }

    /** 裁决里落在题目范围内的 1 起序号去重排序；空裁决按无问题处理。 */
    private static List<Integer> validIndices(ReviewVerdict verdict, int size) {
        if (verdict == null || verdict.failedItems() == null) {
            return List.of();
        }
        return verdict.failedItems().stream()
                .filter(item -> item != null && item.index() >= 1 && item.index() <= size)
                .map(ReviewVerdict.FailedItem::index)
                .distinct()
                .sorted()
                .toList();
    }

    private String reviewUser(List<ReportItem> items) {
        StringBuilder sb = new StringBuilder();
        sb.append("共 ").append(items.size()).append(" 题，逐题评分结果如下（序号从 1 开始）：\n");
        int index = 1;
        for (ReportItem item : items) {
            sb.append("【第").append(index++).append("题】")
                    .append(truncate(item.question(), 50))
                    .append(" | 得分 ").append(item.score())
                    .append(" | 回答摘要 ").append(truncate(item.userAnswerSummary(), 60))
                    .append(" | 评语 ").append(truncate(item.comment(), 100)).append('\n');
        }
        return sb.toString();
    }

    /** 参考回答阶段：评分闭环收口后按批定制范文，点评指出的缺口重点覆盖；失败留空不阻断。 */
    private void fillReferenceAnswers(List<QaPair> pairs, List<ReportItem> items) {
        for (int from = 0; from < items.size(); from += EVAL_BATCH) {
            int start = from;
            int end = Math.min(from + EVAL_BATCH, items.size());
            try {
                AnswerEvaluation result = invoker.invoke(ReportPrompts.ANSWERER_SYSTEM,
                        answerUser(pairs, items, start, end), AnswerEvaluation.class);
                if (result == null || result.answers() == null) {
                    throw new IllegalStateException("参考回答输出为空");
                }
                for (AnswerEvaluation.AnswerEntry entry : result.answers()) {
                    if (entry == null || entry.index() < 1 || entry.index() > end - start
                            || entry.referenceAnswer() == null || entry.referenceAnswer().isBlank()) {
                        continue;
                    }
                    ReportItem item = items.get(start + entry.index() - 1);
                    items.set(start + entry.index() - 1, withAnswer(item,
                            truncate(entry.referenceAnswer().strip(), ANSWER_STORE_MAX_CHARS)));
                }
            } catch (RuntimeException e) {
                System.err.printf("[报告生成] 参考回答批失败，该批留空：%s%n", e.getMessage());
            }
        }
    }

    /** 题目用配对原文而非评分员的概括：范文要贴住面试官实际问的场景。 */
    private String answerUser(List<QaPair> pairs, List<ReportItem> items, int from, int to) {
        StringBuilder sb = new StringBuilder();
        sb.append("共 ").append(to - from).append(" 题需要参考回答（序号从 1 开始）：\n");
        int index = 1;
        for (int i = from; i < to; i++) {
            ReportItem item = items.get(i);
            sb.append("\n【第").append(index++).append("题】\n题目：")
                    .append(compress(pairs.get(i).question(), QUESTION_MAX_CHARS))
                    .append("\n候选人得分：").append(item.score())
                    .append("\n点评：").append(truncate(item.comment(), 120)).append('\n');
        }
        return sb.toString();
    }

    private static ReportItem withComment(ReportItem item, String comment) {
        return new ReportItem(item.question(), item.userAnswerSummary(), comment,
                item.score(), item.referenceAnswer());
    }

    private static ReportItem withAnswer(ReportItem item, String referenceAnswer) {
        return new ReportItem(item.question(), item.userAnswerSummary(), item.comment(),
                item.score(), referenceAnswer);
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
