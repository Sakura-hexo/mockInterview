package com.sakura.interview.api;

import java.io.IOException;
import java.util.List;

/**
 * 服务端语音合成端口：文本进、mp3 字节出。voice 必须来自 voices() 清单。
 * 前置事实：浏览器 TTS 不可靠（Chrome 无代理只有旧 SAPI、Edge 在线嗓音对网页静默失效），
 * 服务端合成是稳定听感的唯一路径；实现方可以是免费通道（edge）或云厂商试用。
 */
public interface TtsSpeaker {

    /** 可用嗓音清单，空列表表示 TTS 未启用（接口 404，前端回落浏览器朗读）。 */
    List<String> voices();

    /** 合成 mp3 音频字节，失败抛 IOException 由控制器转 502。 */
    byte[] synthesize(String text, String voice) throws IOException;
}
