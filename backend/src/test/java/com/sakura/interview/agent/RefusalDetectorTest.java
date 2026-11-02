package com.sakura.interview.agent;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 拒绝检测：整条短消息命中拒绝词汇才算，长文本里的「不会」是作答不误判。
 */
class RefusalDetectorTest {

    @Test
    void 常见拒绝短消息命中() {
        assertTrue(RefusalDetector.isRefusal("不要"));
        assertTrue(RefusalDetector.isRefusal("这题不会"));
        assertTrue(RefusalDetector.isRefusal("不知道，没学过"));
        assertTrue(RefusalDetector.isRefusal("换个话题"));
        assertTrue(RefusalDetector.isRefusal("下一题吧"));
        assertTrue(RefusalDetector.isRefusal("跳过"));
        assertTrue(RefusalDetector.isRefusal("忘了"));
        assertTrue(RefusalDetector.isRefusal("pass"));
    }

    @Test
    void 长作答不误判() {
        assertFalse(RefusalDetector.isRefusal(
                "我不会用Redis实现分布式锁，但当时我们用数据库唯一索引做了兜底方案，效果也还行"));
        assertFalse(RefusalDetector.isRefusal("我介绍一下我的项目背景：三年Java后端，主要负责订单服务"));
        assertFalse(RefusalDetector.isRefusal("这道题我想想，先从线程安全的场景说起吧"));
    }

    @Test
    void 空与无关短消息不命中() {
        assertFalse(RefusalDetector.isRefusal(null));
        assertFalse(RefusalDetector.isRefusal("   "));
        assertFalse(RefusalDetector.isRefusal("我的项目经验很丰富"));
        assertFalse(RefusalDetector.isRefusal("面试官你好"));
    }
}
