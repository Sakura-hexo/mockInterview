package com.sakura.interview.api;

/**
 * 业务异常：携带 HTTP 状态与业务码（保持一致，前端 http.ts 按 HTTP 状态与信封 code 双口径处理）。
 */
public final class ApiException extends RuntimeException {

    private final int httpStatus;

    public ApiException(int httpStatus, String message) {
        super(message);
        this.httpStatus = httpStatus;
    }

    public static ApiException notFound(String message) {
        return new ApiException(404, message);
    }

    public static ApiException conflict(String message) {
        return new ApiException(409, message);
    }

    public static ApiException unauthorized(String message) {
        return new ApiException(401, message);
    }

    public static ApiException badRequest(String message) {
        return new ApiException(400, message);
    }

    public static ApiException tooLarge(String message) {
        return new ApiException(413, message);
    }

    public static ApiException tooManyRequests(String message) {
        return new ApiException(429, message);
    }

    public int httpStatus() {
        return httpStatus;
    }
}
