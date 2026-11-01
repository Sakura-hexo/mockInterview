import { describe, expect, it } from 'vitest';
import { deriveStage, formatDuration, nextPollDelay, REPORT_STAGES } from './reportProgress';

describe('nextPollDelay 轮询节奏', () => {
  it('前 5 次为 3 秒，之后退避到 10 秒封顶', () => {
    expect(nextPollDelay(0)).toBe(3000);
    expect(nextPollDelay(4)).toBe(3000);
    expect(nextPollDelay(5)).toBe(10000);
    expect(nextPollDelay(20)).toBe(10000);
  });
});

describe('deriveStage 阶段推导', () => {
  it('每 2 次轮询推进一个阶段并封顶', () => {
    expect(deriveStage(0)).toBe(0);
    expect(deriveStage(1)).toBe(0);
    expect(deriveStage(2)).toBe(1);
    expect(deriveStage(4)).toBe(2);
    expect(deriveStage(6)).toBe(3);
    expect(deriveStage(99)).toBe(3);
  });

  it('阶段文案与接口文档 4.5 流程一致', () => {
    expect(REPORT_STAGES.length).toBe(4);
    expect(REPORT_STAGES[0]).toBe('面试消息汇总');
    expect(REPORT_STAGES[3]).toBe('汇总输出');
  });
});

describe('formatDuration 时长格式化', () => {
  it('毫秒转 分 秒', () => {
    expect(formatDuration(1938000)).toBe('32 分 18 秒');
    expect(formatDuration(0)).toBe('0 分 0 秒');
    expect(formatDuration(65000)).toBe('1 分 5 秒');
  });

  it('负数与非法输入按 0 处理', () => {
    expect(formatDuration(-100)).toBe('0 分 0 秒');
  });
});
