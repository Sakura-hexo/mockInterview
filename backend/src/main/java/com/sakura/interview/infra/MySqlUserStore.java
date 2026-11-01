package com.sakura.interview.infra;

import com.sakura.interview.api.UserStore;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;

import javax.sql.DataSource;
import java.sql.PreparedStatement;
import java.sql.Statement;
import java.util.List;
import java.util.Optional;

/**
 * UserStore 的 MySQL 实现：t_user 表，密码只存 BCrypt 散列。
 * 用户名唯一冲突转译为可读业务异常，不把 SQL 状态泄露给上层。
 */
public final class MySqlUserStore implements UserStore {

    private final JdbcTemplate jdbc;

    public MySqlUserStore(DataSource dataSource) {
        this.jdbc = new JdbcTemplate(dataSource);
    }

    @Override
    public long create(String username, String passwordHash) {
        KeyHolder keys = new GeneratedKeyHolder();
        try {
            jdbc.update(connection -> {
                PreparedStatement ps = connection.prepareStatement(
                        "INSERT INTO t_user (username, password_hash) VALUES (?, ?)",
                        Statement.RETURN_GENERATED_KEYS);
                ps.setString(1, username);
                ps.setString(2, passwordHash);
                return ps;
            }, keys);
        } catch (DuplicateKeyException e) {
            throw new IllegalStateException("用户名已被占用：" + username);
        }
        Number key = keys.getKey();
        if (key == null) {
            throw new IllegalStateException("用户创建未返回自增 id");
        }
        return key.longValue();
    }

    @Override
    public Optional<UserRecord> findByUsername(String username) {
        List<UserRecord> found = jdbc.query(
                "SELECT id, username, password_hash FROM t_user WHERE username = ?",
                (rs, i) -> new UserRecord(rs.getLong("id"), rs.getString("username"),
                        rs.getString("password_hash")),
                username);
        return found.isEmpty() ? Optional.empty() : Optional.of(found.get(0));
    }
}
