package com.sakura.interview.api;

/**
 * 管理员门禁：env ADMIN_USERNAME 指定用户名即管理员；未配置则对所有用户都拒绝
 * （管理接口 404 零暴露）。函数式接口便于装配与单测分别给实现。
 */
@FunctionalInterface
public interface AdminGate {

    boolean isOwner(String username);

    /** 按指定用户名构造：空串视为管理功能整体关闭。 */
    static AdminGate forUsername(String adminUsername) {
        if (adminUsername == null || adminUsername.isBlank()) {
            return username -> false;
        }
        return adminUsername::equals;
    }
}
