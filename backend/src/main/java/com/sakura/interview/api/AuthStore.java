package com.sakura.interview.api;

/**
 * 认证会话端口：令牌的发放核销与登录限速，生产实现为 Redis（infra），单测用内存实现。
 * 会话令牌为不透明随机串，不编码任何信息（认证方案 2026-11-01 第 2.2 节）。
 */
public interface AuthStore {

    void saveSession(String token, long userId);

    /** 解析令牌对应用户；命中即滑动续期，无效或过期返回 null。 */
    Long resolveUserId(String token);

    void deleteSession(String token);

    boolean isLoginLocked(String username);

    /** 记一次登录失败并刷新锁定窗口，达到阈值后进入锁定。 */
    void recordLoginFailure(String username);

    void clearLoginFailures(String username);
}
