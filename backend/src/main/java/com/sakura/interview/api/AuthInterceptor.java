package com.sakura.interview.api;

import com.google.gson.Gson;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.MediaType;
import org.springframework.web.servlet.HandlerInterceptor;

import java.nio.charset.StandardCharsets;

/**
 * 认证拦截器：拦 /api/**（放行 /api/auth/**），查 Redis 会话并注入 userId 请求属性，
 * 命中即滑动续期；无效令牌直接写 401 信封并中断——前端 http.ts 收 401 清令牌跳登录。
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
        String authorization = request.getHeader("Authorization");
        Long userId = null;
        if (authorization != null && authorization.startsWith("Bearer ")) {
            userId = authStore.resolveUserId(authorization.substring("Bearer ".length()).trim());
        }
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
}
