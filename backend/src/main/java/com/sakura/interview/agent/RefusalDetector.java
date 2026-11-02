package com.sakura.interview.agent;

import java.util.regex.Pattern;

/**
 * 候选人拒绝检测：整条消息很短且命中拒绝类词汇时，判定为当前题结束信号。
 * 只认整条短消息——长文本里出现「不会」是在作答，不能误判。
 * 背景：2026-11-02 三轮纯提示词迭代（含最高优先级规则与句式封禁）均被模型的
 * 劝答社交本能压过，实测中等与困难档仍回到原题，故升级为本轮级指令注入
 * （Prompts.REFUSAL_DIRECTIVE，由 Supervisor 按本检测器结果追加）。
 */
final class RefusalDetector {

    /** 拒绝类词汇：不要、不想、不会、不知道、忘、跳过、换一道、下一题等。 */
    private static final Pattern REFUSAL = Pattern.compile(
            "不要|不想|不会|不知道|没听过|没学过|忘了|跳过|换一?[道个题]|下一题|pass");

    /** 整条消息超过该长度视为正常作答，不再按拒绝处理。 */
    private static final int MAX_LENGTH = 20;

    private RefusalDetector() {
    }

    static boolean isRefusal(String userText) {
        if (userText == null) {
            return false;
        }
        String text = userText.strip();
        return text.length() <= MAX_LENGTH && REFUSAL.matcher(text).find();
    }
}
