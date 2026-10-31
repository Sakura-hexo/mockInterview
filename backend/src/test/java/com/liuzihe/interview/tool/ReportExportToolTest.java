package com.liuzihe.interview.tool;

import com.liuzihe.interview.session.InterviewReport;
import com.liuzihe.interview.session.ReportItem;
import com.liuzihe.interview.session.ReportScore;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ReportExportToolTest {

    @Test
    void 导出含三段结构与评分明细() {
        InterviewReport report = new InterviewReport("s-001",
                new ReportScore(7, 6, 8, 5),
                List.of(new ReportItem("HashMap 底层结构", "答了数组加链表", "漏了红黑树与树化条件", 6)),
                List.of("补并发容器体系", "追问时先复述问题再作答"),
                12_000, 300_000);
        String markdown = new ReportExportTool().export(report);
        assertThat(markdown)
                .contains("# 面试复盘报告")
                .contains("## 评分")
                .contains("## 逐题回顾")
                .contains("## 改进建议")
                .contains("| 总体 | 7/10 |")
                .contains("HashMap 底层结构")
                .contains("漏了红黑树")
                .contains("补并发容器体系");
    }

    @Test
    void 空建议与空题目的兜底渲染() {
        InterviewReport report = new InterviewReport("s-002",
                new ReportScore(5, 5, 5, 5), List.of(), List.of(), 0, 0);
        String markdown = new ReportExportTool().export(report);
        assertThat(markdown).contains("（本轮无题目记录）").contains("- 无");
    }

    @Test
    void 报告为空抛异常() {
        assertThatThrownBy(() -> new ReportExportTool().export(null))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void 评分越界抛异常() {
        assertThatThrownBy(() -> new ReportScore(11, 5, 5, 5))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new ReportItem("题", "答", "评", -1))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
