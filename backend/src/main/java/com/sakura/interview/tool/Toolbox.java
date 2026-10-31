package com.sakura.interview.tool;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Supplier;

/**
 * 工具统一注册中心：按名注册与获取、调用计时日志、异常兜底包装。
 * 各工具自身的降级策略（如 JD 解析降级）在工具内部实现，本类只提供通用包装。
 */
public final class Toolbox {

    /** 工具元信息：名称与描述，供步骤 4 生成智能体工具说明与步骤 7 排查。 */
    public record ToolMeta(String name, String description) {
    }

    /** 一次工具调用的记录：名称、成败、耗时毫秒。 */
    public record ToolCall(String tool, boolean success, long millis) {
    }

    private final Map<String, ToolMeta> metas = new LinkedHashMap<>();
    private final Map<String, Object> instances = new LinkedHashMap<>();
    private final List<ToolCall> history = new CopyOnWriteArrayList<>();

    public Toolbox register(String name, String description, Object tool) {
        metas.put(name, new ToolMeta(name, description));
        instances.put(name, tool);
        return this;
    }

    public boolean has(String name) {
        return metas.containsKey(name);
    }

    public ToolMeta meta(String name) {
        return metas.get(name);
    }

    public <T> T get(String name, Class<T> type) {
        Object tool = instances.get(name);
        if (tool == null) {
            throw new IllegalStateException("工具未注册：" + name);
        }
        return type.cast(tool);
    }

    /** 计时调用：成功与否都记录历史，异常原样上抛；仅限已注册工具。 */
    public <T> T invoke(String name, Supplier<T> action) {
        requireRegistered(name);
        return timed(name, action);
    }

    /** 兜底调用：主路径失败时走 fallback 并显式打日志，fallback 调用不要求注册。 */
    public <T> T invokeOrFallback(String name, Supplier<T> action, Supplier<T> fallback) {
        try {
            requireRegistered(name);
            return timed(name, action);
        } catch (RuntimeException e) {
            System.err.println("[Toolbox] " + name + " 调用失败，走兜底：" + e.getMessage());
            return timed(name + ":fallback", fallback);
        }
    }

    private <T> T timed(String name, Supplier<T> action) {
        long start = System.nanoTime();
        try {
            T result = action.get();
            record(name, true, start);
            return result;
        } catch (RuntimeException e) {
            record(name, false, start);
            throw e;
        }
    }

    public List<ToolCall> history() {
        return List.copyOf(history);
    }

    private void requireRegistered(String name) {
        if (!instances.containsKey(name)) {
            throw new IllegalStateException("工具未注册：" + name);
        }
    }

    private void record(String name, boolean success, long start) {
        long millis = (System.nanoTime() - start) / 1_000_000;
        history.add(new ToolCall(name, success, millis));
        System.out.printf("[Toolbox] %s | %s | %dms%n", name, success ? "成功" : "失败", millis);
    }
}
