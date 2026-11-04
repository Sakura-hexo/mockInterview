package com.sakura.interview.agent;

import java.util.List;

/**
 * 提前收尾话术检测：未问满时面试官回复命中全场收尾语义即视为漂移。
 * 只在 Supervisor 闸门里与「无选题标记」联合判定，单独命中不触发（比如单题结束
 * 后正常追问也可能带「这道题到此结束」），避免误伤追问轮。
 * 语义清单从真机提前收尾样本归纳，宁缺勿滥——漏网的由报告配对兜底。
 */
final class FarewellDetector {

    private static final List<String> CONCLUSION_MARKERS = List.of(
            "面试结束", "面试到此", "到这里就", "到此结束", "全部结束", "全部问完",
            "题目都问", "所有题", "感谢你的时间", "感谢参与", "感谢分享",
            "祝你", "祝您", "期待你", "再见");

    private FarewellDetector() {
    }

    /** 是否全场收尾语义：命中任一标记词即真。 */
    static boolean isConclusion(String text) {
        if (text == null || text.isBlank()) {
            return false;
        }
        return CONCLUSION_MARKERS.stream().anyMatch(text::contains);
    }
}
