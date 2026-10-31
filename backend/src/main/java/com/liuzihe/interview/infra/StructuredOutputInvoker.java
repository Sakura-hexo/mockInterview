package com.liuzihe.interview.infra;

import com.google.gson.Gson;
import com.google.gson.JsonParseException;

import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 统一结构化输出调用器：全项目需要 JSON 输出的调用都走这里，禁止业务代码各写解析。
 * 流程：追加格式要求、剥离代码围栏、本地修复常见 JSON 瑕疵、
 * 解析失败带具体原因回填重试（默认 3 次），耗尽抛业务异常；全程计数埋点，步骤 8 换 Prometheus。
 */
public final class StructuredOutputInvoker {

    private static final String FORMAT_SUFFIX = "只输出一个合法的 JSON 对象：不要 markdown 代码块，不要解释文字。";
    private static final Gson GSON = new Gson();

    private final LlmCaller caller;
    private final int maxAttempts;
    private final AtomicInteger calls = new AtomicInteger();
    private final AtomicInteger retries = new AtomicInteger();
    private final AtomicInteger failures = new AtomicInteger();
    private final AtomicLong totalNanos = new AtomicLong();

    public StructuredOutputInvoker(LlmCaller caller) {
        this(caller, 3);
    }

    public StructuredOutputInvoker(LlmCaller caller, int maxAttempts) {
        this.caller = caller;
        this.maxAttempts = Math.max(1, maxAttempts);
    }

    public <T> T invoke(String systemPrompt, String taskInput, Class<T> resultType) {
        String task = taskInput.strip() + "\n\n" + FORMAT_SUFFIX;
        String lastError = "无输出";
        for (int attempt = 1; attempt <= maxAttempts; attempt++) {
            calls.incrementAndGet();
            String prompt = attempt == 1 ? task
                    : task + "\n\n上次输出解析失败：" + lastError + "。请修正后重新只输出合法 JSON。";
            long start = System.nanoTime();
            String reply;
            try {
                reply = caller.complete(systemPrompt, prompt);
            } catch (RuntimeException e) {
                lastError = "调用异常：" + e.getMessage();
                continue;
            }
            totalNanos.addAndGet(System.nanoTime() - start);
            String json = extractJson(reply);
            if (json == null) {
                lastError = "回复中未找到 JSON 对象";
                retries.incrementAndGet();
                continue;
            }
            try {
                return GSON.fromJson(repair(json), resultType);
            } catch (JsonParseException | IllegalStateException e) {
                lastError = e.getMessage();
                retries.incrementAndGet();
            }
        }
        failures.incrementAndGet();
        throw new IllegalStateException("结构化输出解析失败（已尝试 " + maxAttempts + " 次）：" + lastError);
    }

    /** 调用统计，供埋点与排查使用。 */
    public record Stats(int calls, int retries, int failures, long totalMillis) {
    }

    public Stats stats() {
        return new Stats(calls.get(), retries.get(), failures.get(), totalNanos.get() / 1_000_000);
    }

    /** 剥离 markdown 围栏并截取首个大括号到末个大括号的片段。 */
    static String extractJson(String text) {
        if (text == null || text.isBlank()) {
            return null;
        }
        String stripped = text.replace("```json", "").replace("```JSON", "").replace("```", "").strip();
        int start = stripped.indexOf('{');
        int end = stripped.lastIndexOf('}');
        if (start < 0 || end <= start) {
            return null;
        }
        return stripped.substring(start, end + 1);
    }

    /** 常见 JSON 瑕疵的本地修复：零宽字符与尾逗号；更复杂的修复交给带原因的重试。 */
    static String repair(String json) {
        return json
                .replace("﻿", "")
                .replace("​", "")
                .replaceAll(",\\s*([}\\]])", "$1");
    }
}
