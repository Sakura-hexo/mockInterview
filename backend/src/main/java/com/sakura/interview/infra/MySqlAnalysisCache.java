package com.sakura.interview.infra;

import com.sakura.interview.session.AnalysisCache;
import org.springframework.jdbc.core.JdbcTemplate;

import javax.sql.DataSource;
import java.util.List;
import java.util.Optional;

/**
 * 分析缓存 MySQL 实现：建表见 sql/analysis-schema.sql（随启动幂等执行）。
 * 写入用 upsert——并发重复建会话双写无害，确定性内容相同；无锁无淘汰。
 */
public final class MySqlAnalysisCache implements AnalysisCache {

    private final JdbcTemplate jdbc;

    public MySqlAnalysisCache(DataSource dataSource) {
        this.jdbc = new JdbcTemplate(dataSource);
    }

    @Override
    public Optional<String> load(String cacheKey) {
        List<String> rows = jdbc.queryForList(
                "SELECT result FROM t_analysis_cache WHERE cache_key = ?", String.class, cacheKey);
        return rows.stream().findFirst();
    }

    @Override
    public void save(String cacheKey, String json) {
        jdbc.update("INSERT INTO t_analysis_cache (cache_key, result) VALUES (?, ?) "
                        + "ON DUPLICATE KEY UPDATE result = VALUES(result)",
                cacheKey, json);
    }
}
