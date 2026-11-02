package com.sakura.interview.rag;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * 评测集 Markdown 解析器。格式约定：
 * ## 编号 开启一条，随后为 查询：期望：形态： 必填字段与 备注： 可选字段。
 * 期望为 NONE 表示题库外负例（解析为空列表）；期望题号逗号分隔支持多金标。
 * 任何一条必填字段缺失即抛异常并带编号，宁可失败也不静默跑脏评测。
 */
public final class EvalSetParser {

    private static final String ID_PREFIX = "## ";
    private static final String QUERY_PREFIX = "查询：";
    private static final String GOLD_PREFIX = "期望：";
    private static final String TIER_PREFIX = "形态：";
    private static final String NOTE_PREFIX = "备注：";
    private static final String NONE_GOLD = "NONE";

    private EvalSetParser() {
    }

    public static List<EvalCase> parse(Path file) {
        List<EvalCase> cases = new ArrayList<>();
        Cursor cursor = new Cursor();
        for (String raw : readLines(file)) {
            String line = raw.strip();
            if (line.startsWith(ID_PREFIX)) {
                cursor.flush(cases);
                cursor.id = line.substring(ID_PREFIX.length()).strip();
            } else if (line.startsWith(QUERY_PREFIX)) {
                cursor.query = line.substring(QUERY_PREFIX.length()).strip();
            } else if (line.startsWith(GOLD_PREFIX)) {
                cursor.gold = line.substring(GOLD_PREFIX.length()).strip();
            } else if (line.startsWith(TIER_PREFIX)) {
                cursor.tier = line.substring(TIER_PREFIX.length()).strip();
            } else if (line.startsWith(NOTE_PREFIX)) {
                cursor.note = line.substring(NOTE_PREFIX.length()).strip();
            }
        }
        cursor.flush(cases);
        if (cases.isEmpty()) {
            throw new IllegalStateException("评测集文件没有解析到任何条目：" + file);
        }
        return cases;
    }

    private static List<String> toGoldIds(String gold) {
        if (gold == null || gold.isBlank() || NONE_GOLD.equalsIgnoreCase(gold.strip())) {
            return List.of();
        }
        return Arrays.stream(gold.split("[，,]"))
                .map(String::strip)
                .filter(s -> !s.isEmpty())
                .toList();
    }

    private static List<String> readLines(Path file) {
        try {
            return Files.readAllLines(file, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new IllegalStateException("读取评测集文件失败：" + file, e);
        }
    }

    /** 单条解析中的临时字段集合。 */
    private static final class Cursor {
        private String id;
        private String query;
        private String gold;
        private String tier;
        private String note;

        private void flush(List<EvalCase> out) {
            if (id == null) {
                return;
            }
            if (isBlank(query) || gold == null || isBlank(tier)) {
                throw new IllegalStateException("评测条目必填字段不完整：" + id);
            }
            out.add(new EvalCase(id, query, toGoldIds(gold), tier, note));
            id = null;
            query = null;
            gold = null;
            tier = null;
            note = null;
        }

        private static boolean isBlank(String value) {
            return value == null || value.isBlank();
        }
    }
}
