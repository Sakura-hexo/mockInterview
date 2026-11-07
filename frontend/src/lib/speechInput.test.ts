import { describe, expect, it } from 'vitest';
import {
  appendTranscript,
  formatRecordElapsed,
  recorderMimeType,
  speechErrorMessage,
  speechSupported,
  toSimplifiedChinese,
  type SpeechRecognitionConstructor,
  type SpeechRecognitionLike,
} from './speechInput';

class FakeRecognition implements SpeechRecognitionLike {
  lang = '';
  continuous = false;
  interimResults = false;
  onresult = null;
  onerror = null;
  onend = null;
  start() {}
  stop() {}
  abort() {}
}

const FakeRecognitionConstructor =
  FakeRecognition as unknown as SpeechRecognitionConstructor;

describe('speechSupported', () => {
  it('支持标准 SpeechRecognition', () => {
    expect(speechSupported({ SpeechRecognition: FakeRecognitionConstructor })).toBe(true);
  });

  it('兼容 webkit 前缀', () => {
    expect(speechSupported({ webkitSpeechRecognition: FakeRecognitionConstructor })).toBe(true);
  });

  it('不支持时返回 false', () => {
    expect(speechSupported({})).toBe(false);
  });
});

describe('appendTranscript', () => {
  it('将常见繁体识别结果转成简体', () => {
    expect(toSimplifiedChinese('測試 清潔')).toBe('测试 清洁');
    expect(appendTranscript('', '測試 能不能清潔')).toBe('测试 能不能清洁');
  });

  it('空草稿直接追加识别文本', () => {
    expect(appendTranscript('', ' 我熟悉 Java 并发 ')).toBe('我熟悉 Java 并发');
  });

  it('已有草稿时补充间隔并保留文本', () => {
    expect(appendTranscript('我的项目是', '电商秒杀系统')).toBe('我的项目是 电商秒杀系统');
  });

  it('草稿以标点结尾时不额外加空格', () => {
    expect(appendTranscript('我的项目是，', '电商秒杀系统')).toBe('我的项目是，电商秒杀系统');
  });

  it('空识别结果不改变草稿', () => {
    expect(appendTranscript('已有回答', '   ')).toBe('已有回答');
  });
});

describe('speechErrorMessage', () => {
  it('将常见错误转成用户可理解文案', () => {
    expect(speechErrorMessage('not-allowed')).toContain('麦克风');
    expect(speechErrorMessage('no-speech')).toContain('未识别');
    expect(speechErrorMessage('unknown')).toContain('重试');
  });
});

describe('recorderMimeType', () => {
  it('优先返回 opus 编码的 webm 格式', () => {
    expect(
      recorderMimeType({ MediaRecorder: { isTypeSupported: (t) => t === 'audio/webm;codecs=opus' } }),
    ).toBe('audio/webm;codecs=opus');
  });

  it('webm 不支持时逐级回落到 mp4', () => {
    expect(
      recorderMimeType({ MediaRecorder: { isTypeSupported: (t) => t === 'audio/mp4' } }),
    ).toBe('audio/mp4');
  });

  it('无 MediaRecorder 或全不支持时返回空串交浏览器默认', () => {
    expect(recorderMimeType({})).toBe('');
    expect(recorderMimeType({ MediaRecorder: { isTypeSupported: () => false } })).toBe('');
  });
});

describe('formatRecordElapsed', () => {
  it('不足一分钟显示 0 加两位秒', () => {
    expect(formatRecordElapsed(0)).toBe('0:00');
    expect(formatRecordElapsed(8_400)).toBe('0:08');
    expect(formatRecordElapsed(59_999)).toBe('0:59');
  });

  it('过分钟进位补零', () => {
    expect(formatRecordElapsed(60_000)).toBe('1:00');
    expect(formatRecordElapsed(65_000)).toBe('1:05');
    expect(formatRecordElapsed(120_000)).toBe('2:00');
  });

  it('负数按 0 处理不出现负秒', () => {
    expect(formatRecordElapsed(-500)).toBe('0:00');
  });
});
