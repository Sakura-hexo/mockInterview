package com.liuzihe.interview.rag;

import com.liuzihe.interview.infra.EmbeddingModels;
import com.liuzihe.interview.infra.EnvKeys;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Stream;

/**
 * 步骤 2 命令行入口：题库导入与混合检索验证。
 * import [--rebuild]；search 查询 [--topK N] [--dense-only] [--no-rewrite]
 * 运行：mvn compile exec:java -Dexec.mainClass=com.liuzihe.interview.rag.RagCliRunner -Dexec.args="..."
 */
public final class RagCliRunner {

    private static final Path BANK_DIR = Path.of("question-bank");

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
        List<Path> files = bankFiles();
        List<BankQuestion> questions = new ArrayList<>();
        for (Path file : files) {
            questions.addAll(QuestionBankParser.parse(file));
        }
        Set<String> ids = new HashSet<>();
        for (BankQuestion q : questions) {
            if (!ids.add(q.id())) {
                throw new IllegalStateException("题号重复，请检查各题库文件：" + q.id());
            }
        }
        if (rebuild) {
            store.rebuild();
            System.out.println("已重建集合，换 embedding 模型后必须走此路径。");
        } else {
            store.ensureCollection();
        }
        int imported = store.importQuestions(questions);
        System.out.printf("题库文件 %d 个 | 解析 %d 题 | 本次入库 %d 题 | 跳过已存在 %d 题%n",
                files.size(), questions.size(), imported, questions.size() - imported);
    }

    private static List<Path> bankFiles() {
        try (Stream<Path> stream = Files.list(BANK_DIR)) {
            List<Path> files = stream.filter(p -> p.toString().endsWith(".md")).sorted().toList();
            if (files.isEmpty()) {
                throw new IllegalStateException("question-bank 目录下没有 Markdown 题库文件");
            }
            return files;
        } catch (IOException e) {
            throw new IllegalStateException("扫描题库目录失败：" + BANK_DIR, e);
        }
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
        if (hasFlag(args, "--sparse-only")) {
            print("纯稀疏检索", store.sparseSearch(query, topK));
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
                if (arg.equals("--topK") || arg.equals("--dense-only") || arg.equals("--sparse-only")
                        || arg.equals("--no-rewrite") || arg.equals("--rebuild")) {
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
