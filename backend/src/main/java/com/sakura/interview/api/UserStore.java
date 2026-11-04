package com.sakura.interview.api;

import java.util.Optional;

/**
 * 用户存储端口：注册写入与用户名查询，生产实现为 MySQL（infra），单测用内存实现。
 */
public interface UserStore {

    /** 新建用户，返回自增 id；用户名唯一冲突由实现方抛数据异常。 */
    long create(String username, String passwordHash);

    Optional<UserRecord> findByUsername(String username);

    /** 按自增 id 查用户：会话鉴权注入的是 userId，管理端校验用户名时反查。 */
    Optional<UserRecord> findById(long id);

    record UserRecord(long id, String username, String passwordHash) {
    }
}
