package com.sakura.interview.api;

import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;

import java.security.SecureRandom;
import java.util.HexFormat;
import java.util.regex.Pattern;

/**
 * 认证服务：注册即登录、登录核验与限速、登出即时失效。
 * 密码落库前 BCrypt 散列、日志不落密码；令牌为 32 字节随机 hex 不编码任何信息。
 * 规则（认证方案 2026-11-01 第 5 节已确认项）：开放注册、密码最短 8 位、
 * 同用户名连续失败 5 次锁定 1 分钟。
 */
public final class AuthService {

    private static final Pattern USERNAME_PATTERN = Pattern.compile("[\\w\\u4e00-\\u9fa5]{3,32}");
    private static final int MIN_PASSWORD_LENGTH = 8;

    private final UserStore users;
    private final AuthStore sessions;
    private final BCryptPasswordEncoder encoder = new BCryptPasswordEncoder();
    private final SecureRandom random = new SecureRandom();

    public AuthService(UserStore users, AuthStore sessions) {
        this.users = users;
        this.sessions = sessions;
    }

    public record AuthResult(String token, String username) {
    }

    /** 注册即登录：校验通过、散列落库、直接发放会话令牌。 */
    public AuthResult register(String username, String password) {
        validate(username, password);
        long userId;
        try {
            userId = users.create(username, encoder.encode(password));
        } catch (IllegalStateException e) {
            throw ApiException.conflict("用户名已被占用");
        }
        return issueToken(username, userId);
    }

    public AuthResult login(String username, String password) {
        if (sessions.isLoginLocked(username)) {
            throw new ApiException(429, "失败次数过多，账号已锁定 1 分钟，请稍后再试");
        }
        UserStore.UserRecord user = users.findByUsername(username)
                .orElseThrow(() -> new ApiException(401, "用户名或密码错误"));
        if (!encoder.matches(password, user.passwordHash())) {
            sessions.recordLoginFailure(username);
            throw ApiException.unauthorized("用户名或密码错误");
        }
        sessions.clearLoginFailures(username);
        return issueToken(username, user.id());
    }

    /** 登出：删除 Redis 会话，令牌即时失效。 */
    public void logout(String token) {
        if (token != null && !token.isBlank()) {
            sessions.deleteSession(token);
        }
    }

    private AuthResult issueToken(String username, long userId) {
        byte[] bytes = new byte[32];
        random.nextBytes(bytes);
        String token = HexFormat.of().formatHex(bytes);
        sessions.saveSession(token, userId);
        return new AuthResult(token, username);
    }

    private static void validate(String username, String password) {
        if (username == null || !USERNAME_PATTERN.matcher(username).matches()) {
            throw ApiException.badRequest("用户名需为 3 到 32 位字母数字下划线或中文");
        }
        if (password == null || password.length() < MIN_PASSWORD_LENGTH) {
            throw ApiException.badRequest("密码至少 8 位");
        }
    }
}
