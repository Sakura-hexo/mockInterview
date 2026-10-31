package com.sakura.interview.tool;

import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ResumeParseToolTest {

    @Test
    void 纯文本直通并去首尾空白() {
        String text = new ResumeParseTool().extract("  Java 后端工程师，三年经验  ");
        assertThat(text).isEqualTo("Java 后端工程师，三年经验");
    }

    @Test
    void 空文本抛异常() {
        assertThatThrownBy(() -> new ResumeParseTool().extract("   "))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void 测试内生成PDF并经Tika提取() throws Exception {
        byte[] pdf = buildPdf("Java Backend Engineer Resume with Spring Boot");
        String text = new ResumeParseTool().extract(pdf);
        assertThat(text).contains("Java Backend Engineer");
    }

    @Test
    void 超过五兆抛异常() {
        byte[] oversized = new byte[5 * 1024 * 1024 + 1];
        assertThatThrownBy(() -> new ResumeParseTool().extract(oversized))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("5MB");
    }

    @Test
    void 空文件抛异常() {
        assertThatThrownBy(() -> new ResumeParseTool().extract(new byte[0]))
                .isInstanceOf(IllegalArgumentException.class);
    }

    /** 用 PDFBox 生成一页带文本的最小 PDF，避免依赖外部夹具文件。 */
    private static byte[] buildPdf(String content) throws Exception {
        try (PDDocument doc = new PDDocument()) {
            PDPage page = new PDPage();
            doc.addPage(page);
            try (PDPageContentStream stream = new PDPageContentStream(doc, page)) {
                stream.beginText();
                stream.setFont(new PDType1Font(Standard14Fonts.FontName.HELVETICA), 12);
                stream.newLineAtOffset(50, 700);
                stream.showText(content);
                stream.endText();
            }
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            doc.save(out);
            return out.toByteArray();
        }
    }
}
