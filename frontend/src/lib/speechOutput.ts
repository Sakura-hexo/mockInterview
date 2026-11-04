// 语音输出（面试官读题）：浏览器 SpeechSynthesis 的纯函数封装，与 speechInput 同构——
// lib 只做可测的纯逻辑与依赖注入类型，React 状态归 useSpeechOutput，接线归页面与气泡。
// 云端合成（DashScope 等）不做（P1 排期决策：轻量方案，零成本）。

export interface SpeechSynthesisVoiceLike {
  lang: string;
  name: string;
}

export interface SpeechSynthesisUtteranceLike {
  text: string;
  lang: string;
  rate: number;
  voice: SpeechSynthesisVoiceLike | null;
  onend: (() => void) | null;
  onerror: ((event: { error?: string }) => void) | null;
}

export interface SpeechSynthesisLike {
  readonly speaking: boolean;
  /** 参数取 DOM 类型，真实 window.speechSynthesis 才能结构匹配本接口。 */
  speak(utterance: SpeechSynthesisUtterance): void;
  cancel(): void;
  getVoices(): SpeechSynthesisVoiceLike[];
}

/** 返回真实 DOM 语句类型，让 window 的原生构造器可直接传入；测试假件经双转型注入。 */
export type SpeechSynthesisUtteranceConstructor = new (text: string) => SpeechSynthesisUtterance;

export interface SpeechOutputSource {
  speechSynthesis?: SpeechSynthesisLike;
  SpeechSynthesisUtterance?: SpeechSynthesisUtteranceConstructor;
}

export function speechOutputSupported(source: SpeechOutputSource): boolean {
  return source.speechSynthesis !== undefined && source.SpeechSynthesisUtterance !== undefined;
}

/** 中文嗓音优先 zh-CN，其次任意 zh；都没有则返回 null，交给 utterance.lang 兜底。泛型保调用方拿到原元素类型。 */
export function pickChineseVoice<T extends SpeechSynthesisVoiceLike>(voices: T[]): T | null {
  const chinese = voices.filter((voice) => voice.lang.toLowerCase().startsWith('zh'));
  const mandarin = chinese.find(
    (voice) => voice.lang.toLowerCase().replace('_', '-').startsWith('zh-cn'),
  );
  return mandarin ?? chinese[0] ?? null;
}

/**
 * 剥掉 Markdown 朗读噪音。代码内容保留（题干里的 `LIMIT 1000000, 10` 是要念出来的），
 * 只去标记符号：围栏、行内反引号、加粗斜体、标题井号、链接、列表符号、表格竖线。
 */
export function stripMarkdown(text: string): string {
  return text
    .replace(/```[a-zA-Z0-9-]*\n?/g, '')
    .replace(/`([^`]*)`/g, '$1')
    .replace(/!?\[([^\]]*)\]\([^)]*\)/g, '$1')
    .replace(/(\*\*|__)(.*?)\1/g, '$2')
    .replace(/(\*|_)([^*_]+)\1/g, '$2')
    .replace(/^\s{0,3}#{1,6}\s+/gm, '')
    .replace(/^\s*[-*+]\s+/gm, '')
    .replace(/^\s*\d+\.\s+/gm, '')
    .replace(/\s*\|\s*/g, ' ')
    .replace(/[*>#]+/g, '')
    .replace(/\n{2,}/g, '\n')
    .trim();
}

/**
 * 按句读切分逐句入队：Chrome 桌面版长段朗读约 15 秒会停，面试官一轮 300 字纪律
 * 下按句切已足够短。分隔符含换行；无分隔符的尾巴单独成句，空串全部滤掉。
 */
export function splitSentences(text: string): string[] {
  const sentences: string[] = [];
  let current = '';
  for (const character of text) {
    current += character;
    if (character === '\n' || '。！？；!?;'.includes(character)) {
      const trimmed = current.trim();
      if (trimmed) sentences.push(trimmed);
      current = '';
    }
  }
  const rest = current.trim();
  if (rest) sentences.push(rest);
  return sentences;
}

/** 用户主动停止触发的错误码不算失败，朗读被替换同理——不算错误。 */
export function isSilentCancel(error?: string): boolean {
  return error === 'canceled' || error === 'interrupted';
}

export function speechOutputErrorMessage(event: { error?: string }): string {
  const code = event.error;
  if (code === 'not-allowed') return '浏览器未授权语音播放，请在地址栏允许后重试';
  if (code === 'synthesis-failed') return '语音合成失败，请重试';
  if (code === 'network') return '语音合成服务网络异常，请检查网络后重试';
  return '语音播放失败，请重试';
}
