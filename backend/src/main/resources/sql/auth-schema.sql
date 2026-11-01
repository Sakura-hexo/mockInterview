-- 账号体系（用户认证改造方案 2026-11-01 第 2.4 节）
-- 密码只存 BCrypt 散列，永不落明文；面试会话经 t_session.user_id 归属到用户

CREATE TABLE IF NOT EXISTS t_user (
  id            BIGINT AUTO_INCREMENT PRIMARY KEY,
  username      VARCHAR(64) NOT NULL,
  password_hash VARCHAR(100) NOT NULL,
  created_at    TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
  CONSTRAINT uk_user_username UNIQUE (username)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4;
