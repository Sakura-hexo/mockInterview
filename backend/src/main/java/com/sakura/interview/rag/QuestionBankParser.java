package com.sakura.interview.rag;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * 题库 Markdown 解析器。格式约定：
 * ## 题号 开启一题，随后各行为 题干：要点：标签：来源： 四个前缀字段。
 * 任何一题字段不完整即抛异常并带题号，宁可失败也不静默入库脏数据。
 */
public final class QuestionBankParser {

    private static final String ID_PREFIX = "## ";
    private static final String QUESTION_PREFIX = "题干：";
    private static final String POINTS_PREFIX = "要点：";
    private static final String TAGS_PREFIX = "标签：";
    private static final String SOURCE_PREFIX = "来源：";

    private QuestionBankParser() {
    }

    public static List<BankQuestion> parse(Path file) {
        List<BankQuestion> questions = new ArrayList<>();
        Cursor cursor = new Cursor();
        for (String raw : readLines(file)) {
            String line = raw.strip();
            if (line.startsWith(ID_PREFIX)) {
                cursor.flush(questions);
                cursor.id = line.substring(ID_PREFIX.length()).strip();
            } else if (line.startsWith(QUESTION_PREFIX)) {
                cursor.question = line.substring(QUESTION_PREFIX.length()).strip();
            } else if (line.startsWith(POINTS_PREFIX)) {
                cursor.points = line.substring(POINTS_PREFIX.length()).strip();
            } else if (line.startsWith(TAGS_PREFIX)) {
                cursor.tags = line.substring(TAGS_PREFIX.length()).strip();
            } else if (line.startsWith(SOURCE_PREFIX)) {
                cursor.source = line.substring(SOURCE_PREFIX.length()).strip();
            }
        }
        cursor.flush(questions);
        if (questions.isEmpty()) {
            throw new IllegalStateException("题库文件没有解析到任何题目：" + file);
        }
        return questions;
    }

    private static List<String> readLines(Path file) {
        try {
            return Files.readAllLines(file, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new IllegalStateException("读取题库文件失败：" + file, e);
        }
    }

    /** 单题解析中的临时字段集合。 */
    private static final class Cursor {
        private String id;
        private String question;
        private String points;
        private String tags;
        private String source;

        private void flush(List<BankQuestion> out) {
            if (id == null) {
                return;
            }
            if (isBlank(question) || isBlank(points) || isBlank(tags) || isBlank(source)) {
                throw new IllegalStateException("题目字段不完整：" + id);
            }
            out.add(new BankQuestion(id, question, points, tags, source));
            id = null;
            question = null;
            points = null;
            tags = null;
            source = null;
        }

        private static boolean isBlank(String value) {
            return value == null || value.isBlank();
        }
    }
}
