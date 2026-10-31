package com.sakura.interview.session;

/**
 * 评估配对：面试官的题目与候选人对其的完整回答（可能由多条消息拼接）。
 * 首条面试官消息之前的候选人输入（自我介绍）与 chat 闲聊消息不构成配对。
 */
public record QaPair(String question, String answer) {
}
