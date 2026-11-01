package com.sakura.interview.api;

import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 认证接口（接口文档 4.0）：注册、登录、登出。
 * 拦截器放行 /api/auth/**，登出从请求头取令牌注销服务端会话。
 */
@RestController
@RequestMapping("/api/auth")
public final class AuthController {

    private final AuthService authService;

    public AuthController(AuthService authService) {
        this.authService = authService;
    }

    public record Credentials(String username, String password) {
    }

    @PostMapping("/register")
    public ApiEnvelope<AuthService.AuthResult> register(@RequestBody Credentials body) {
        return ApiEnvelope.ok(authService.register(body.username(), body.password()));
    }

    @PostMapping("/login")
    public ApiEnvelope<AuthService.AuthResult> login(@RequestBody Credentials body) {
        return ApiEnvelope.ok(authService.login(body.username(), body.password()));
    }

    @PostMapping("/logout")
    public ApiEnvelope<Void> logout(
            @RequestHeader(value = "Authorization", required = false) String authorization) {
        authService.logout(bearerToken(authorization));
        return ApiEnvelope.ok();
    }

    private static String bearerToken(String authorization) {
        if (authorization == null || !authorization.startsWith("Bearer ")) {
            return null;
        }
        return authorization.substring("Bearer ".length()).trim();
    }
}
