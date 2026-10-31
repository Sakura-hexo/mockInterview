package com.sakura.interview.agent;

import io.agentscope.core.ReActAgent;
import io.agentscope.core.model.Model;
import io.agentscope.core.state.InMemoryAgentStateStore;

/**
 * 最小面试官智能体工厂：角色由系统提示词锁定，
 * 多轮记忆由 stateStore 按 userId 与 sessionId 累积。
 * 模型经参数注入，来源由 ChatModels 按 MODEL_PROVIDER 分派。
 */
public final class InterviewerAgent {

    private InterviewerAgent() {
    }

    public static ReActAgent create(Model model) {
        return ReActAgent.builder()
                .name("interviewer")
                .sysPrompt(InterviewerPrompts.SYSTEM_PROMPT)
                .model(model)
                .stateStore(new InMemoryAgentStateStore())
                .build();
    }
}
