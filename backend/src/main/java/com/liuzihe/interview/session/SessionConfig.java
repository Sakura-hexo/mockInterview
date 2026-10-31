package com.liuzihe.interview.session;

/**
 * 面试配置。字段取值以接口文档第 3 节为准：
 * direction 如 JAVA_BACKEND，difficulty 如 EASY、MEDIUM、HARD，枚举校验在接口层做。
 */
public record SessionConfig(String direction, String difficulty, int questionCount) {
}
