package com.sakura.interview.rag;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class EvalSetParserTest {

    @TempDir
    Path temp;

    @Test
    void 解析正例多金标与NONE负例() throws Exception {
        Path file = temp.resolve("eval-set.md");
        Files.writeString(file, """
                # 评测集

                ## E-001
                查询：索引失效
                期望：QB-013
                形态：术语直查
                备注：基线

                ## E-002
                查询：秒杀怎么防超卖
                期望：MY-007，RD-008
                形态：口语化

                ## E-003
                查询：Vue 响应式原理
                期望：NONE
                形态：题库外
                """);

        List<EvalCase> cases = EvalSetParser.parse(file);

        assertThat(cases).hasSize(3);
        assertThat(cases.get(0).query()).isEqualTo("索引失效");
        assertThat(cases.get(0).goldIds()).containsExactly("QB-013");
        assertThat(cases.get(0).note()).isEqualTo("基线");
        assertThat(cases.get(1).goldIds()).containsExactly("MY-007", "RD-008");
        assertThat(cases.get(2).goldIds()).isEmpty();
    }

    @Test
    void 必填字段缺失抛异常带编号() throws Exception {
        Path file = temp.resolve("eval-set.md");
        Files.writeString(file, """
                ## E-001
                查询：索引失效
                形态：术语直查
                """);

        assertThatThrownBy(() -> EvalSetParser.parse(file))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("E-001");
    }

    @Test
    void 空文件抛异常() throws Exception {
        Path file = temp.resolve("eval-set.md");
        Files.writeString(file, "# 评测集\n");

        assertThatThrownBy(() -> EvalSetParser.parse(file))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("没有解析到任何条目");
    }
}
