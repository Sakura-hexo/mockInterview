package com.sakura.interview.api;

import jakarta.servlet.http.HttpServletResponse;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseCookie;
import org.springframework.web.bind.annotation.CookieValue;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.Duration;

/**
 * 认证接口（接口文档 4.0，2026-11-02 Cookie 模式修订）：注册、登录、登出。
 * 会话令牌只经 HttpOnly Cookie 下发，响应体不含令牌，JS 全程接触不到凭据；
 * 登出同属性清 Cookie 并删服务端会话。
 */
@RestController
@RequestMapping("/api/auth")
public final class AuthController {

    /** 会话 Cookie 名，拦截器与登出共用（认证方案 2026-11-02 Cookie 模式）。 */
    public static final String SESSION_COOKIE = "INTERVIEW_SESSION";

    private final AuthService authService;
    private final boolean cookieSecure;

    public AuthController(AuthService authService,
                          @Value("${auth.cookie.secure:false}") boolean cookieSecure) {
        this.authService = authService;
        this.cookieSecure = cookieSecure;
    }

    public record Credentials(String username, String password) {
    }

    /** 响应体只回用户名，令牌仅走 Cookie。 */
    public record SessionView(String username) {
    }

    @PostMapping("/register")
    public ApiEnvelope<SessionView> register(@RequestBody Credentials body,
                                             HttpServletResponse response) {
        return issue(authService.register(body.username(), body.password()), response);
    }

    @PostMapping("/login")
    public ApiEnvelope<SessionView> login(@RequestBody Credentials body,
                                          HttpServletResponse response) {
        return issue(authService.login(body.username(), body.password()), response);
    }

    @PostMapping("/logout")
    public ApiEnvelope<Void> logout(
            @CookieValue(value = SESSION_COOKIE, required = false) String token,
            HttpServletResponse response) {
        authService.logout(token);
        response.addHeader(HttpHeaders.SET_COOKIE, sessionCookie("").maxAge(0).build().toString());
        return ApiEnvelope.ok();
    }

    private ApiEnvelope<SessionView> issue(AuthService.AuthResult result,
                                           HttpServletResponse response) {
        response.addHeader(HttpHeaders.SET_COOKIE,
                sessionCookie(result.token()).build().toString());
        return ApiEnvelope.ok(new SessionView(result.username()));
    }

    private ResponseCookie.ResponseCookieBuilder sessionCookie(String token) {
        return ResponseCookie.from(SESSION_COOKIE, token)
                .httpOnly(true)
                .sameSite("Strict")
                .path("/api")
                .maxAge(Duration.ofHours(24))
                .secure(cookieSecure);
    }
}
