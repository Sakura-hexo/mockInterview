package com.liuzihe.interview.tool;

import org.apache.tika.Tika;
import org.apache.tika.exception.TikaException;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;

/**
 * 简历解析工具：纯文本直通；PDF 等二进制格式上限 5MB 经 Tika 提取原文。
 * 提取失败显式抛异常不静默；扫描件提取不到文本属 P1 视觉解析场景。
 */
public final class ResumeParseTool {

    private static final int MAX_FILE_BYTES = 5 * 1024 * 1024;
    private static final int MAX_TEXT_CHARS = 2_000_000;

    private final Tika tika = new Tika();

    /** 纯文本简历直通。 */
    public String extract(String rawText) {
        if (rawText == null || rawText.isBlank()) {
            throw new IllegalArgumentException("简历文本为空");
        }
        return rawText.strip();
    }

    /** 二进制简历文件（PDF 等）经 Tika 提取。 */
    public String extract(byte[] fileBytes) {
        if (fileBytes == null || fileBytes.length == 0) {
            throw new IllegalArgumentException("简历文件为空");
        }
        if (fileBytes.length > MAX_FILE_BYTES) {
            throw new IllegalArgumentException("简历文件超过 5MB 上限");
        }
        tika.setMaxStringLength(MAX_TEXT_CHARS);
        try (InputStream in = new ByteArrayInputStream(fileBytes)) {
            String text = tika.parseToString(in);
            if (text == null || text.isBlank()) {
                throw new IllegalStateException("简历文件未提取到文本，可能是扫描件，属 P1 视觉解析场景");
            }
            return text.strip();
        } catch (IOException | TikaException e) {
            throw new IllegalStateException("简历文件解析失败：" + e.getMessage(), e);
        }
    }
}
