package com.liuzihe.interview.rag;

import com.liuzihe.interview.infra.EmbeddingModels;
import com.liuzihe.interview.infra.EnvKeys;

import java.nio.file.Path;
import java.util.List;

/**
 * 步骤 2 命令行入口：题库导入与混合检索验证。
 * import [--rebuild]；search 查询 [--topK N] [--dense-only] [--no-rewrite]
 * 运行：mvn compile exec:java -Dexec.mainClass=com.liuzihe.interview.rag.RagCliRunner -Dexec.args="..."
 */
public final class RagCliRunner {

    private static final Path BANK_FILE = Path.of("question-bank", "java-backend.md");

    private RagCliRunner() {
    }

    public static void main(String[] args) {
        if (args.length == 0) {
            System.err.println("用法：import [--rebuild] | search 查询 [--topK N] [--dense-only] [--no-rewrite]");
            System.exit(2);
        }
        try (MilvusQuestionStore store = new MilvusQuestionStore(EmbeddingModels.embedding())) {
            System.out.println("模型源 " + EnvKeys.modelProvider() + " | embedding " + store.embeddingName()
                    + " | 维度 " + store.dimension() + " | 集合 " + EnvKeys.milvusCollection());
            switch (args[0]) {
                case "import" -> runImport(store, hasFlag(args, "--rebuild"));
                case "search" -> runSearch(store, args);
                default -> {
                    System.err.println("未知命令：" + args[0]);
                    System.exit(2);
                }
            }
        } catch (Exception e) {
            System.err.println("执行失败：" + e.getMessage());
            e.printStackTrace();
            System.exit(1);
        }
    }

    private static void runImport(MilvusQuestionStore store, boolean rebuild) {
        List<BankQuestion> questions = QuestionBankParser.parse(BANK_FILE);
        if (rebuild) {
            store.rebuild();
            System.out.println("已重建集合，换 embedding 模型后必须走此路径。");
        } else {
            store.ensureCollection();
        }
        int imported = store.importQuestions(questions);
        System.out.printf("题库解析 %d 题 | 本次入库 %d 题 | 跳过已存在 %d 题%n",
                questions.size(), imported, questions.size() - imported);
    }

    private static void runSearch(MilvusQuestionStore store, String[] args) {
        String query = queryArg(args);
        if (query == null || query.isBlank()) {
            System.err.println("search 命令需要查询词");
            System.exit(2);
        }
        store.ensureCollection();
        int topK = intFlag(args, "--topK", SearchTiers.topK(query));
        if (hasFlag(args, "--dense-only")) {
            print("纯稠密检索", store.denseSearch(query, topK));
            return;
        }
        if (hasFlag(args, "--no-rewrite")) {
            print("混合检索", store.hybridSearch(query, topK));
            return;
        }
        String rewritten = QueryRewriter.rewrite(query);
        if (rewritten.equals(query)) {
            print("混合检索", store.hybridSearch(query, topK));
            return;
        }
        System.out.println("查询改写：" + rewritten);
        List<SearchHit> fused = SearchTiers.fuse(
                store.hybridSearch(query, topK),
                store.hybridSearch(rewritten, topK),
                topK);
        print("混合检索加双查询融合", fused);
    }

    private static void print(String title, List<SearchHit> hits) {
        System.out.println("== " + title + " ==");
        if (hits.isEmpty()) {
            System.out.println("（无命中）");
            return;
        }
        int rank = 1;
        for (SearchHit hit : hits) {
            System.out.printf("%d. [%s] score=%.4f %s | 标签:%s | 来源:%s%n",
                    rank++, hit.id(), hit.score(), hit.question(), hit.tags(), hit.source());
        }
    }

    private static boolean hasFlag(String[] args, String flag) {
        for (String arg : args) {
            if (flag.equals(arg)) {
                return true;
            }
        }
        return false;
    }

    private static int intFlag(String[] args, String flag, int fallback) {
        for (int i = 0; i < args.length - 1; i++) {
            if (flag.equals(args[i])) {
                try {
                    return Integer.parseInt(args[i + 1]);
                } catch (NumberFormatException e) {
                    return fallback;
                }
            }
        }
        return fallback;
    }

    private static String queryArg(String[] args) {
        for (int i = 1; i < args.length; i++) {
            String arg = args[i];
            if (arg.startsWith("--")) {
                if (arg.equals("--topK") || arg.equals("--dense-only") || arg.equals("--no-rewrite")
                        || arg.equals("--rebuild")) {
                    if (arg.equals("--topK")) {
                        i++;
                    }
                }
                continue;
            }
            return arg;
        }
        return null;
    }
}
