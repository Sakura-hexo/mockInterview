import { describe, expect, it } from 'vitest';
import {
  createVoiceGate,
  describeVoiceState,
  litBars,
  meterLevel,
  rmsLevel,
  MIN_SPEECH_MS,
  SILENCE_MS,
  SPEECH_THRESHOLD,
} from './voiceActivity';

describe('rmsLevel 音量计算', () => {
  it('静音返回 0，满幅方波返回 1', () => {
    expect(rmsLevel(new Float32Array(0))).toBe(0);
    expect(rmsLevel(new Float32Array([0, 0, 0, 0]))).toBe(0);
    expect(rmsLevel(new Float32Array([1, -1, 1, -1]))).toBeCloseTo(1);
  });

  it('等幅正弦的有效值约为峰值 1/根号2', () => {
    const n = 1024;
    const samples = new Float32Array(n);
    for (let i = 0; i < n; i += 1) samples[i] = Math.sin((2 * Math.PI * i) / 64);
    expect(rmsLevel(samples)).toBeCloseTo(Math.SQRT1_2, 2);
  });
});

describe('meterLevel / litBars 显示电平', () => {
  it('负值与零收敛到 0，不会出现负柱', () => {
    expect(meterLevel(0)).toBe(0);
    expect(meterLevel(-1)).toBe(0);
    expect(litBars(-1, 10)).toBe(0);
  });

  it('开方压缩把小音量抬起来，且上限封顶 1', () => {
    expect(meterLevel(0.01)).toBeGreaterThan(0.15);
    expect(meterLevel(0.1)).toBeLessThan(1);
    expect(meterLevel(1)).toBe(1);
    expect(litBars(1, 12)).toBe(12);
  });

  it('格数为 0 时不除零', () => {
    expect(litBars(0.5, 0)).toBe(0);
  });
});

describe('createVoiceGate 说完自动断句', () => {
  const loud = SPEECH_THRESHOLD * 3;
  const quiet = SPEECH_THRESHOLD / 3;
  const step = 100;

  /** 按固定步长推电平序列，返回首次触发的时刻（未触发返回 null）。 */
  const run = (gate: ReturnType<typeof createVoiceGate>, levels: number[]) => {
    let now = 0;
    for (const level of levels) {
      now += step;
      if (gate.push(level, now)) return now;
    }
    return null;
  };

  const repeat = (level: number, count: number) => Array.from({ length: count }, () => level);

  it('先开口再持续安静够久才返回 true', () => {
    const gate = createVoiceGate();
    const speechFrames = Math.ceil(MIN_SPEECH_MS / step) + 2;
    const quietFrames = Math.ceil(SILENCE_MS / step) + 1;
    const hit = run(gate, [...repeat(quiet, 2), ...repeat(loud, speechFrames), ...repeat(quiet, quietFrames)]);
    expect(hit).not.toBeNull();
  });

  it('一直没开口时永不自动停止（允许用户思考）', () => {
    const gate = createVoiceGate();
    const hit = run(gate, repeat(quiet, 200));
    expect(hit).toBeNull();
    expect(gate.hasSpoken()).toBe(false);
  });

  it('说话过程中间短暂停顿不算说完', () => {
    const gate = createVoiceGate();
    const speechFrames = Math.ceil(MIN_SPEECH_MS / step) + 2;
    const shortPause = Math.floor(SILENCE_MS / step / 2);
    const frames = [...repeat(loud, speechFrames), ...repeat(quiet, shortPause), ...repeat(loud, 5)];
    expect(run(gate, frames)).toBeNull();
    expect(gate.hasSpoken()).toBe(true);
  });

  it('瞬时噪声（短于最小发声时长）不构成开口', () => {
    const gate = createVoiceGate();
    const frames = [
      ...repeat(loud, 1),
      ...repeat(quiet, Math.ceil(SILENCE_MS / step) * 2),
    ];
    expect(run(gate, frames)).toBeNull();
  });

  it('reset 后重新开始判定', () => {
    const gate = createVoiceGate();
    run(gate, repeat(loud, 5));
    expect(gate.hasSpoken()).toBe(true);
    gate.reset();
    expect(gate.hasSpoken()).toBe(false);
    expect(run(gate, repeat(quiet, 100))).toBeNull();
  });

  it('时间戳未推进时不误判（首帧只登记基线）', () => {
    const gate = createVoiceGate();
    expect(gate.push(loud, 1000)).toBe(false);
    expect(gate.push(loud, 1000)).toBe(false);
  });
});

describe('describeVoiceState 状态优先级', () => {
  const base = {
    streaming: false,
    recovering: false,
    speaking: false,
    listening: false,
    transcribing: false,
    supported: true,
  };

  it('不支持麦克风时优先告知兜底方案', () => {
    expect(describeVoiceState({ ...base, supported: false, streaming: true }).tone).toBe('idle');
  });

  it('面试官生成 > 读题 > 识别 > 聆听 > 待命', () => {
    expect(describeVoiceState({ ...base, streaming: true, speaking: true }).tone).toBe('thinking');
    expect(describeVoiceState({ ...base, speaking: true, listening: true }).tone).toBe('speaking');
    expect(describeVoiceState({ ...base, transcribing: true, listening: true }).tone).toBe('thinking');
    expect(describeVoiceState({ ...base, listening: true }).tone).toBe('listening');
    expect(describeVoiceState(base).tone).toBe('idle');
  });

  it('轮询同步期间也提示面试官在忙', () => {
    expect(describeVoiceState({ ...base, recovering: true }).text).toContain('组织回复');
  });
});
