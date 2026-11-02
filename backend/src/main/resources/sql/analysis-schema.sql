-- 创建会话分析缓存：简历摘要与岗位画像按输入哈希复用（确定性提取，重启命中）
-- 无淘汰无 TTL：单人规模行数极小，重复分析同一份简历才是真正的浪费

CREATE TABLE IF NOT EXISTS t_analysis_cache (
  cache_key  CHAR(64) PRIMARY KEY,
  result     TEXT NOT NULL,
  created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4;
