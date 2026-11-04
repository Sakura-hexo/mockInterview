package com.sakura.interview.agent;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 提前收尾话术检测单测：只验证词面命中语义；
 * 闸门的「无选题标记才触发」联合判定由 SupervisorTest 覆盖。
 */
class FarewellDetectorTest {

    @Test
    void 命中全场收尾话术() {
        assertThat(FarewellDetector.isConclusion("本场题目到这里就全部结束了，感谢你的时间。")).isTrue();
        assertThat(FarewellDetector.isConclusion("今天的面试就到这里，祝你后续顺利。")).isTrue();
        assertThat(FarewellDetector.isConclusion("所有题都问完了，感谢参与。")).isTrue();
        assertThat(FarewellDetector.isConclusion("面试到此结束，再见。")).isTrue();
    }

    @Test
    void 正常追问与新题不命中() {
        assertThat(FarewellDetector.isConclusion("追问：ThreadLocal 什么场景会内存泄漏？")).isFalse();
        assertThat(FarewellDetector.isConclusion("这道题换个角度：索引失效的场景有哪些？")).isFalse();
        assertThat(FarewellDetector.isConclusion("方向是对的，但少了量化数据，具体压测数字是多少？")).isFalse();
    }

    @Test
    void 空与null不命中() {
        assertThat(FarewellDetector.isConclusion(null)).isFalse();
        assertThat(FarewellDetector.isConclusion("  ")).isFalse();
        assertThat(FarewellDetector.isConclusion("")).isFalse();
    }

    @Test
    void 单题结束的粗判语义为命中_由闸门配合标记判定兜住误伤() {
        // 词面检测刻意放宽：「这道题到此结束」与「面试到此结束」无法只靠词面区分，
        // 闸门只在无选题标记时触发，带标记的正常换题轮不受影响
        assertThat(FarewellDetector.isConclusion("这道题到此结束。")).isTrue();
    }
}
