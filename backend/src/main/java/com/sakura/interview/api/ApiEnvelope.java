package com.sakura.interview.api;

/**
 * 统一响应信封（接口文档第 1 节）：code 为 0 表示成功，
 * 非 0 业务码沿用 HTTP 语义数字（401 未认证、404 不存在、409 状态冲突、413 超限）。
 */
public record ApiEnvelope<T>(int code, String message, T data) {

    public static final int CODE_OK = 0;

    public static <T> ApiEnvelope<T> ok(T data) {
        return new ApiEnvelope<>(CODE_OK, "ok", data);
    }

    public static ApiEnvelope<Void> ok() {
        return new ApiEnvelope<>(CODE_OK, "ok", null);
    }
}
