package com.sakura.interview.api;

import java.io.IOException;

/**
 * 服务端语音识别端口：浏览器录音字节进、识别文本出。
 * 前置事实：浏览器 Web Speech 的识别请求发往国外服务，大陆必须挂代理——
 * 重启丢代理即"网络异常"；服务端本地识别是免代理的稳定路径。
 */
public interface AsrTranscriber {

    /** 是否启用（false 时接口 404，前端回落浏览器识别）。 */
    boolean enabled();

    /** 识别音频字节（浏览器 MediaRecorder 产出的 webm 等格式），失败抛 IOException 由控制器转 502。 */
    String transcribe(byte[] audio) throws IOException;
}
