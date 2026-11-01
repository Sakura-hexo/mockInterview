-- 会话持久化三表：MySQL 为事实源，Redis 只做热存缓存（可随时从本表重建）
-- 状态机：CREATED → IN_PROGRESS → ENDED → REPORTED（接口文档第 2 节）
-- user_id 为账号归属，CLI 创建的会话为 NULL（接口层一律按 userId 过滤，不对外可见）

CREATE TABLE IF NOT EXISTS t_session (
  session_id           VARCHAR(36) PRIMARY KEY,
  user_id              BIGINT NULL,
  resume_digest        TEXT NOT NULL,
  jd_direction         VARCHAR(255) NOT NULL,
  jd_skills            TEXT NOT NULL,
  jd_bonus             TEXT NOT NULL,
  asked_questions_json TEXT,
  round_no             INT NOT NULL DEFAULT 0,
  difficulty           VARCHAR(16) NOT NULL DEFAULT 'MEDIUM',
  question_count       INT NOT NULL DEFAULT 10,
  status               VARCHAR(20) NOT NULL DEFAULT 'CREATED',
  created_at           TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
  ended_at             TIMESTAMP NULL,
  KEY idx_session_user (user_id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4;

CREATE TABLE IF NOT EXISTS t_message (
  session_id    VARCHAR(36) NOT NULL,
  seq           INT NOT NULL,
  role          VARCHAR(16) NOT NULL,
  content       TEXT NOT NULL,
  input_tokens  BIGINT NOT NULL DEFAULT 0,
  output_tokens BIGINT NOT NULL DEFAULT 0,
  action        VARCHAR(16) NULL,
  created_at    TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (session_id, seq)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4;

CREATE TABLE IF NOT EXISTS t_context_summary (
  session_id   VARCHAR(36) PRIMARY KEY,
  summary_text TEXT NOT NULL,
  covered_seq  INT NOT NULL,
  updated_at   TIMESTAMP DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4;
