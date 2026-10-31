-- 复盘报告任务表：MySQL 任务队列，一场会话一份报告，重试复用同一任务行

CREATE TABLE IF NOT EXISTS t_report (
  task_id     VARCHAR(36) PRIMARY KEY,
  session_id  VARCHAR(36) NOT NULL,
  status      VARCHAR(20) NOT NULL,
  retry_count INT NOT NULL DEFAULT 0,
  report_json LONGTEXT,
  error_msg   VARCHAR(500),
  created_at  TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
  updated_at  TIMESTAMP DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  CONSTRAINT uk_report_session UNIQUE (session_id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4;
