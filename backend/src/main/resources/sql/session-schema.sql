-- 会话持久化三表：MySQL 为事实源，Redis 只做热存缓存（可随时从本表重建）

CREATE TABLE IF NOT EXISTS t_session (
  session_id           VARCHAR(36) PRIMARY KEY,
  resume_digest        TEXT NOT NULL,
  jd_direction         VARCHAR(255) NOT NULL,
  jd_skills            TEXT NOT NULL,
  jd_bonus             TEXT NOT NULL,
  asked_questions_json TEXT,
  round_no             INT NOT NULL DEFAULT 0,
  status               VARCHAR(20) NOT NULL DEFAULT 'ACTIVE',
  created_at           TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
  ended_at             TIMESTAMP NULL
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4;

CREATE TABLE IF NOT EXISTS t_message (
  session_id    VARCHAR(36) NOT NULL,
  seq           INT NOT NULL,
  role          VARCHAR(16) NOT NULL,
  content       TEXT NOT NULL,
  input_tokens  BIGINT NOT NULL DEFAULT 0,
  output_tokens BIGINT NOT NULL DEFAULT 0,
  created_at    TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (session_id, seq)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4;

CREATE TABLE IF NOT EXISTS t_context_summary (
  session_id   VARCHAR(36) PRIMARY KEY,
  summary_text TEXT NOT NULL,
  covered_seq  INT NOT NULL,
  updated_at   TIMESTAMP DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4;
