package com.sakura.interview.infra;

import io.agentscope.core.model.GenerateOptions;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * ChatModels 按参思考开关单测：openai 路线的 GenerateOptions 构建口径——
 * 关闭时显式携带 enable_thinking=false（端点默认可能思考），
 * 开启时不传该参数交给端点按模型默认执行。
 */
class ChatModelsTest {

    @Test
    void 思考关闭显式携带enable_thinking为false() {
        GenerateOptions options = ChatModels.thinkingOptions(false);
        assertThat(options.getAdditionalBodyParams()).containsEntry("enable_thinking", false);
    }

    @Test
    void 思考开启不传enable_thinking交给端点默认() {
        GenerateOptions options = ChatModels.thinkingOptions(true);
        assertThat(options.getAdditionalBodyParams()).doesNotContainKey("enable_thinking");
    }
}
