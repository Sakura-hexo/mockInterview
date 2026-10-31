package com.liuzihe.interview.session;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class ContextAssemblerTest {

    private static StoredMessage msg(int seq, String role, String text) {
        return new StoredMessage(seq, role, text, 0, 0);
    }

    @Test
    void 空窗口返回空串() {
        assertThat(ContextAssembler.assemble(List.of(), null)).isEmpty();
    }

    @Test
    void 首轮仅当前回答无历史段() {
        String assembled = ContextAssembler.assemble(List.of(msg(1, "user", "我是候选人")), null);
        assertThat(assembled)
                .contains("【候选人本轮回答】")
                .contains("我是候选人")
                .doesNotContain("【最近对话】")
                .doesNotContain("【前情摘要】");
        assertThat(assembled.length()).isLessThanOrEqualTo(ContextAssembler.MAX_CONTEXT_CHARS);
    }

    @Test
    void 超预算从最早丢弃总长受控且保最近() {
        List<StoredMessage> window = new ArrayList<>();
        for (int i = 1; i <= 30; i++) {
            window.add(msg(i, i % 2 == 1 ? "user" : "interviewer",
                    "第" + i + "条消息：" + "内容".repeat(300)));
        }
        String assembled = ContextAssembler.assemble(window, "旧摘要：" + "细节".repeat(2000));
        assertThat(assembled.length()).isLessThanOrEqualTo(ContextAssembler.MAX_CONTEXT_CHARS);
        assertThat(assembled)
                .contains("第30条消息")
                .contains("第29条消息")
                .contains("【前情摘要】")
                .doesNotContain("第1条消息：")
                .doesNotContain("第20条消息：");
    }

    @Test
    void 摘要超长被截断() {
        String longSummary = "摘".repeat(ContextAssembler.MAX_SUMMARY_CHARS + 500);
        String assembled = ContextAssembler.assemble(List.of(msg(1, "user", "回答")), longSummary);
        assertThat(assembled.length()).isLessThanOrEqualTo(ContextAssembler.MAX_CONTEXT_CHARS);
        assertThat(assembled).contains("摘要已截断");
    }

    @Test
    void 当前回答超长截尾保留() {
        String huge = "字".repeat(ContextAssembler.MAX_CONTEXT_CHARS + 1000);
        String assembled = ContextAssembler.assemble(List.of(msg(1, "user", huge)), null);
        assertThat(assembled.length()).isLessThanOrEqualTo(ContextAssembler.MAX_CONTEXT_CHARS);
        assertThat(assembled).contains("内容过长已截断");
    }

    @Test
    void 窗口不足预算时全量保留() {
        List<StoredMessage> window = List.of(
                msg(1, "interviewer", "请介绍项目"), msg(2, "user", "订单系统重构"));
        String assembled = ContextAssembler.assemble(window, null);
        assertThat(assembled)
                .contains("【最近对话】")
                .contains("面试官：请介绍项目")
                .contains("【候选人本轮回答】\n订单系统重构");
    }
}
