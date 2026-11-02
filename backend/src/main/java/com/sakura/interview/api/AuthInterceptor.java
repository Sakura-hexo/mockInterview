package com.sakura.interview.api;

import com.google.gson.Gson;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.MediaType;
import org.springframework.web.servlet.HandlerInterceptor;

import java.nio.charset.StandardCharsets;

/**
 * 认证拦截器：拦 /api/**（放行 /api/auth/**），从 HttpOnly Cookie 取会话令牌查 Redis
 * 并注入 userId 请求属性，命中即滑动续期；无有效会话直接写 401 信封并中断——
 * 前端 http.ts 收 401 清登录态跳登录。Bearer 通道已于 2026-11-02 Cookie 模式升级中移除。
 */
public final class AuthInterceptor implements HandlerInterceptor {

    public static final String USER_ID_ATTRIBUTE = "userId";

    private final AuthStore authStore;
    private final Gson gson = new Gson();

    public AuthInterceptor(AuthStore authStore) {
        this.authStore = authStore;
    }

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response,
                             Object handler) throws Exception {
        String token = sessionToken(request);
        Long userId = token == null ? null : authStore.resolveUserId(token);
        if (userId == null) {
            response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
            response.setContentType(MediaType.APPLICATION_JSON_VALUE);
            response.setCharacterEncoding(StandardCharsets.UTF_8.name());
            response.getWriter().write(gson.toJson(
                    new ApiEnvelope<>(401, "未认证或会话已过期", null)));
            return false;
        }
        request.setAttribute(USER_ID_ATTRIBUTE, userId);
        return true;
    }

    private static String sessionToken(HttpServletRequest request) {
        Cookie[] cookies = request.getCookies();
        if (cookies == null) {
            return null;
        }
        for (Cookie cookie : cookies) {
            if (AuthController.SESSION_COOKIE.equals(cookie.getName())) {
                return cookie.getValue();
            }
        }
        return null;
    }
}
