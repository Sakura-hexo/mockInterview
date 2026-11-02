package com.sakura.interview.api;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class AuthServiceTest {

    private final FakeUserStore users = new FakeUserStore();
    private final FakeAuthStore sessions = new FakeAuthStore();
    private final AuthService service = new AuthService(users, sessions);

    @Test
    void 注册即登录发放令牌且密码散列落库() {
        AuthService.AuthResult result = service.register("sakura", "password123");

        assertThat(result.token()).hasSize(64);
        assertThat(result.username()).isEqualTo("sakura");
        assertThat(sessions.sessions).containsValue(1L);
        // 明文永不落库
        assertThat(users.byUsername.get("sakura").passwordHash())
                .startsWith("$2").doesNotContain("password123");
    }

    @Test
    void 注册重名返回409() {
        service.register("sakura", "password123");
        assertThatThrownBy(() -> service.register("sakura", "another456"))
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("已被占用")
                .satisfies(e -> assertThat(((ApiException) e).httpStatus()).isEqualTo(409));
    }

    @Test
    void 密码过短与用户名非法返回400() {
        assertThatThrownBy(() -> service.register("ab", "password123"))
                .isInstanceOf(ApiException.class).hasMessageContaining("用户名");
        assertThatThrownBy(() -> service.register("sakura", "short"))
                .isInstanceOf(ApiException.class).hasMessageContaining("密码");
    }

    @Test
    void 登录成功清失败计数且令牌可解析() {
        service.register("sakura", "password123");
        sessions.loginFailures.put("sakura", 2);

        AuthService.AuthResult result = service.login("sakura", "password123");

        assertThat(sessions.resolveUserId(result.token())).isEqualTo(1L);
        assertThat(sessions.loginFailures).doesNotContainKey("sakura");
    }

    @Test
    void 密码错误记一次失败且不区分用户名不存在() {
        service.register("sakura", "password123");

        assertThatThrownBy(() -> service.login("sakura", "wrongpass"))
                .isInstanceOf(ApiException.class).hasMessageContaining("用户名或密码错误");
        assertThatThrownBy(() -> service.login("nobody", "whatever88"))
                .isInstanceOf(ApiException.class).hasMessageContaining("用户名或密码错误");
        assertThat(sessions.loginFailures.get("sakura")).isEqualTo(1);
        assertThat(sessions.loginFailures).doesNotContainKey("nobody");
    }

    @Test
    void 连续失败五次锁定一分钟() {
        service.register("sakura", "password123");
        for (int i = 0; i < 5; i++) {
            assertThatThrownBy(() -> service.login("sakura", "wrongpass"))
                    .isInstanceOf(ApiException.class);
        }
        // 第五次失败后进入锁定，即使密码正确也拒绝
        assertThatThrownBy(() -> service.login("sakura", "password123"))
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("锁定");
    }

    @Test
    void 登出删除会话令牌即时失效() {
        AuthService.AuthResult result = service.register("sakura", "password123");
        assertThat(sessions.resolveUserId(result.token())).isEqualTo(1L);

        service.logout(result.token());

        assertThat(sessions.resolveUserId(result.token())).isNull();
    }

    @Test
    void 会话解析滑动续期() {
        AuthService.AuthResult result = service.register("sakura", "password123");
        sessions.resolveUserId(result.token());
        sessions.resolveUserId(result.token());
        assertThat(sessions.renewCount).isEqualTo(2);
    }
}
