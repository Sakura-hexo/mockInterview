import { describe, expect, it } from 'vitest';
import {
  chineseVoiceOptions,
  findVoiceByName,
  isSilentCancel,
  pickChineseVoice,
  speechOutputErrorMessage,
  speechOutputSupported,
  splitSentences,
  stripMarkdown,
  type SpeechSynthesisLike,
  type SpeechSynthesisUtteranceConstructor,
  type SpeechSynthesisUtteranceLike,
  type SpeechSynthesisVoiceLike,
} from './speechOutput';

class FakeUtterance implements SpeechSynthesisUtteranceLike {
  text: string;
  lang = '';
  rate = 1;
  voice: SpeechSynthesisVoiceLike | null = null;
  onend: (() => void) | null = null;
  onerror: ((event: { error?: string }) => void) | null = null;

  constructor(text: string) {
    this.text = text;
  }
}

class FakeSynthesis implements SpeechSynthesisLike {
  speaking = false;
  speak(): void {}
  cancel(): void {}
  getVoices(): SpeechSynthesisVoiceLike[] {
    return [];
  }
}

const FakeUtteranceConstructor = FakeUtterance as unknown as SpeechSynthesisUtteranceConstructor;

describe('speechOutputSupported', () => {
  it('合成器与语句构造器双存在才算支持', () => {
    expect(
      speechOutputSupported({ speechSynthesis: new FakeSynthesis(), SpeechSynthesisUtterance: FakeUtteranceConstructor }),
    ).toBe(true);
  });

  it('缺任一即不支持', () => {
    expect(speechOutputSupported({ speechSynthesis: new FakeSynthesis() })).toBe(false);
    expect(speechOutputSupported({})).toBe(false);
  });
});

describe('pickChineseVoice', () => {
  it('zh-CN 优先于其他中文嗓音', () => {
    const voices = [
      { lang: 'zh-TW', name: '台灣嗓音' },
      { lang: 'zh-CN', name: '普通话' },
      { lang: 'en-US', name: 'English' },
    ];
    expect(pickChineseVoice(voices)?.name).toBe('普通话');
  });

  it('没有 zh-CN 时退而求其次取任意 zh', () => {
    expect(pickChineseVoice([{ lang: 'zh_TW', name: '台灣' }])?.name).toBe('台灣');
  });

  it('无中文嗓音返回 null 交给 lang 兜底', () => {
    expect(pickChineseVoice([{ lang: 'en-US', name: 'English' }])).toBeNull();
    expect(pickChineseVoice([])).toBeNull();
  });

  it('同为普通话时本地自然嗓音优先于旧 SAPI', () => {
    const huihui = { lang: 'zh-CN', name: 'Microsoft Huihui - Chinese (Simplified, PRC)' };
    const xiaoxiao = { lang: 'zh-CN', name: 'Microsoft Xiaoxiao Natural - Chinese (Mainland)' };
    expect(pickChineseVoice([huihui, xiaoxiao])?.name).toBe('Microsoft Xiaoxiao Natural - Chinese (Mainland)');
  });

  it('Edge 的 Online 在线嗓音排最后：静默失效不再被自动选中', () => {
    const huihui = { lang: 'zh-CN', name: 'Microsoft Huihui - Chinese (Simplified, PRC)' };
    const online = { lang: 'zh-CN', name: 'Microsoft Xiaoxiao Online (Natural) - Chinese (Mainland)' };
    expect(pickChineseVoice([online, huihui])?.name).toBe(
      'Microsoft Huihui - Chinese (Simplified, PRC)',
    );
  });
});

describe('chineseVoiceOptions 嗓音菜单排序', () => {
  it('普通话优先，其后自然嗓音在旧 SAPI 前，在线嗓音不再抢自动位，其他中文殿后', () => {
    const huihui = { lang: 'zh-CN', name: 'Microsoft Huihui - Chinese (Simplified, PRC)' };
    const yue = { lang: 'zh-HK', name: 'Google 粵語（香港）' };
    const xiaoxiao = { lang: 'zh-CN', name: 'Microsoft Xiaoxiao Natural - Chinese (Mainland)' };
    const online = { lang: 'zh-CN', name: 'Microsoft Xiaoxiao Online (Natural) - Chinese (Mainland)' };
    const english = { lang: 'en-US', name: 'English' };
    const options = chineseVoiceOptions([huihui, english, yue, online, xiaoxiao]);
    expect(options.map((v) => v.name)).toEqual([
      'Microsoft Xiaoxiao Natural - Chinese (Mainland)',
      'Microsoft Huihui - Chinese (Simplified, PRC)',
      'Microsoft Xiaoxiao Online (Natural) - Chinese (Mainland)',
      'Google 粵語（香港）',
    ]);
  });
});

describe('findVoiceByName 按名找嗓音', () => {
  it('精确匹配返回原对象，找不到返回 null', () => {
    const kangkang = { lang: 'zh-CN', name: 'Microsoft Kangkang' };
    expect(findVoiceByName([kangkang], 'Microsoft Kangkang')).toBe(kangkang);
    expect(findVoiceByName([kangkang], '别的')).toBeNull();
  });
});

describe('stripMarkdown', () => {
  it('行内代码与围栏去标记但保留内容', () => {
    expect(stripMarkdown('改写 SQL `LIMIT 1000000, 10` 后')).toBe('改写 SQL LIMIT 1000000, 10 后');
    expect(stripMarkdown('```\nEXPLAIN SELECT\n```')).toBe('EXPLAIN SELECT');
  });

  it('加粗斜体标题链接列表符号全剥', () => {
    expect(stripMarkdown('## 第 3 题\n**重点**是 *缓存一致性*')).toBe('第 3 题\n重点是 缓存一致性');
    expect(stripMarkdown('参考 [官方文档](https://example.com) 即可')).toBe('参考 官方文档 即可');
    expect(stripMarkdown('- 第一点\n- 第二点')).toBe('第一点\n第二点');
    expect(stripMarkdown('1. 先查索引\n2. 再看执行计划')).toBe('先查索引\n再看执行计划');
  });

  it('普通文本原样返回', () => {
    expect(stripMarkdown('谈谈索引失效的场景')).toBe('谈谈索引失效的场景');
  });
});

describe('splitSentences', () => {
  it('按中英文句读切分并保留分隔符', () => {
    expect(splitSentences('第一问。第二问！第三问？')).toEqual(['第一问。', '第二问！', '第三问？']);
  });

  it('换行与分号也是切分点，空句滤掉', () => {
    expect(splitSentences('先说结论；\n\n展开讲讲')).toEqual(['先说结论；', '展开讲讲']);
  });

  it('无分隔符的尾巴单独成句，空串为空数组', () => {
    expect(splitSentences('结尾没有标点')).toEqual(['结尾没有标点']);
    expect(splitSentences('  \n ')).toEqual([]);
  });
});

describe('错误口径', () => {
  it('用户主动停止与被替换是静默取消，不算失败', () => {
    expect(isSilentCancel('canceled')).toBe(true);
    expect(isSilentCancel('interrupted')).toBe(true);
    expect(isSilentCancel('synthesis-failed')).toBe(false);
    expect(isSilentCancel(undefined)).toBe(false);
  });

  it('错误码映射为可操作文案', () => {
    expect(speechOutputErrorMessage({ error: 'not-allowed' })).toContain('未授权');
    expect(speechOutputErrorMessage({ error: 'synthesis-failed' })).toContain('重试');
    expect(speechOutputErrorMessage({})).toContain('失败');
  });
});
