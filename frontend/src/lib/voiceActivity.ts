/**
 * 语音面试模式的活动检测与状态推导。
 *
 * 语音面试要解决的核心体验问题：用户说完一句后，面试要能自己「听出来」他说完了，
 * 而不是让他再点一下鼠标。真正的 VAD（如 silero）跑在服务端 ASR 边车里，前端这里
 * 只做轻量判定：用 AnalyserNode 取时域波形算 RMS，先确认「确实开口了」，再确认
 * 「持续安静够久」，两者同时成立才算一句说完。
 *
 * 全部为纯函数，便于单测（与 lib/speechInput.ts 同风格，不碰 DOM）。
 */

/** 判定为「有人说话」的 RMS 阈值：安静房间约 0.001~0.005，正常说话 0.02~0.2。 */
export const SPEECH_THRESHOLD = 0.015;
/** 累计发声达到该时长才算真的开口，滤掉咳嗽、敲键盘、椅子响这类瞬时噪声。 */
export const MIN_SPEECH_MS = 240;
/** 开口之后连续安静达到该时长判定一句说完。1.5s 给足句中换气的余量。 */
export const SILENCE_MS = 1500;
/** 单帧时间差上限：后台标签页定时器降频时不让一次跳变直接把静音时长吃满。 */
const MAX_FRAME_MS = 600;

export interface VoiceGateOptions {
  threshold?: number;
  minSpeechMs?: number;
  silenceMs?: number;
}

export interface VoiceGate {
  /** 推入一帧电平与时间戳，返回 true 表示「说完了」，调用方应停止录音。 */
  push(level: number, now: number): boolean;
  /** 本轮是否已检测到有效发言（用于区分「没开口」与「开口后停顿」）。 */
  hasSpoken(): boolean;
  reset(): void;
}

/**
 * 创建一句一判的语音闸门。
 *
 * 设计取舍：只有「先开口、后停顿」才返回 true。用户一直没说话（比如正在思考、
 * 或者根本没开麦）时永不自动停止——真实面试里允许思考，机器抢答比多等一下更糟。
 */
export function createVoiceGate(options: VoiceGateOptions = {}): VoiceGate {
  const threshold = options.threshold ?? SPEECH_THRESHOLD;
  const minSpeechMs = options.minSpeechMs ?? MIN_SPEECH_MS;
  const silenceMs = options.silenceMs ?? SILENCE_MS;
  let last = 0;
  let spokenMs = 0;
  let quietMs = 0;
  let spoken = false;

  const reset = () => {
    last = 0;
    spokenMs = 0;
    quietMs = 0;
    spoken = false;
  };

  return {
    reset,
    hasSpoken: () => spoken,
    push(level, now) {
      if (last === 0) {
        last = now;
        return false;
      }
      const delta = Math.min(MAX_FRAME_MS, Math.max(0, now - last));
      last = now;
      if (level >= threshold) {
        quietMs = 0;
        spokenMs += delta;
        if (spokenMs >= minSpeechMs) spoken = true;
        return false;
      }
      quietMs += delta;
      return spoken && quietMs >= silenceMs;
    },
  };
}

/** 时域波形（-1~1 浮点采样）的均方根，作为这一帧的音量。 */
export function rmsLevel(samples: Float32Array): number {
  if (samples.length === 0) return 0;
  let sum = 0;
  for (let i = 0; i < samples.length; i += 1) {
    sum += samples[i] * samples[i];
  }
  return Math.sqrt(sum / samples.length);
}

/**
 * 把 RMS 映射成 0~1 的显示电平。
 * 开方压缩：不压缩的话轻声说话（0.01）和正常说话（0.1）在柱状图上差十倍，看着像没反应。
 */
export function meterLevel(level: number): number {
  if (!(level > 0)) return 0;
  return Math.min(1, Math.sqrt(level / 0.25));
}

/** 电平柱的亮起数量：把 0~1 电平切成固定格数，供 UI 直接渲染。 */
export function litBars(level: number, total: number): number {
  if (total <= 0) return 0;
  return Math.round(meterLevel(level) * total);
}

export type VoiceTone = 'idle' | 'speaking' | 'listening' | 'thinking';

export interface VoiceStateInput {
  /** 面试官消息正在流式生成 */
  streaming: boolean;
  /** 断线后轮询同步中 */
  recovering: boolean;
  /** 面试官正在读题（TTS 播放中） */
  speaking: boolean;
  /** 正在录音 */
  listening: boolean;
  /** 录音已上传，等识别结果 */
  transcribing: boolean;
  /** 麦克风通道可用 */
  supported: boolean;
  /**
   * 是否具备「静音自动断句」：只有服务端录音通道拿得到原始音频。
   * 浏览器 SpeechRecognition 通道拿不到波形，文案不能承诺这件做不到的事。
   */
  autoStop?: boolean;
}

export interface VoiceState {
  tone: VoiceTone;
  text: string;
}

/** 语音面板顶部那一行状态文案：让用户随时知道「现在轮到谁」。 */
export function describeVoiceState(input: VoiceStateInput): VoiceState {
  const autoStop = input.autoStop ?? true;
  if (!input.supported) {
    return { tone: 'idle', text: '当前环境不支持语音识别，可切回键盘输入' };
  }
  if (input.streaming || input.recovering) {
    return { tone: 'thinking', text: '面试官正在组织回复…' };
  }
  if (input.speaking) {
    return { tone: 'speaking', text: '面试官正在提问，听完直接回答即可' };
  }
  if (input.transcribing) {
    return { tone: 'thinking', text: '正在识别你的回答…' };
  }
  if (input.listening) {
    return {
      tone: 'listening',
      text: autoStop ? '正在聆听，说完停顿一下会自动提交' : '正在录音，说完再点一次麦克风结束这段',
    };
  }
  return {
    tone: 'idle',
    text: autoStop ? '点麦克风开始回答，说完停顿 1.5 秒自动提交' : '点麦克风开始回答，说完再点一次麦克风提交',
  };
}
