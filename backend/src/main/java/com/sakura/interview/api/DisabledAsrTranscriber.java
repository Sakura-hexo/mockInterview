package com.sakura.interview.api;

import java.io.IOException;

/** ASR 关闭态：接口 404，前端回落浏览器识别。 */
public final class DisabledAsrTranscriber implements AsrTranscriber {

    @Override
    public boolean enabled() {
        return false;
    }

    @Override
    public String transcribe(byte[] audio) throws IOException {
        throw new IOException("ASR 未启用");
    }
}
