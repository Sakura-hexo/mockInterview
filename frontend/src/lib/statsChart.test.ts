import { describe, expect, it } from 'vitest';
import { averageTokens, fillDailyTrend, formatTokens, localDateKey } from './statsChart';

describe('fillDailyTrend 趋势补零', () => {
  it('铺满连续 N 天并把有数据的天对齐', () => {
    const today = localDateKey(new Date());
    const filled = fillDailyTrend([{ date: today, tokens: 100 }], 3);
    expect(filled).toHaveLength(3);
    expect(filled[2].date).toBe(today);
    expect(filled[2].tokens).toBe(100);
    expect(filled[0].tokens).toBe(0);
    expect(filled[1].tokens).toBe(0);
  });

  it('没有数据时全零但日期连续', () => {
    const filled = fillDailyTrend([], 5);
    expect(filled).toHaveLength(5);
    expect(filled.every((p) => p.tokens === 0)).toBe(true);
  });
});

describe('formatTokens 缩写', () => {
  it('万位以上带一位小数', () => {
    expect(formatTokens(28300)).toBe('2.8万');
    expect(formatTokens(10000)).toBe('1.0万');
  });

  it('万位以下原样输出', () => {
    expect(formatTokens(9800)).toBe('9800');
    expect(formatTokens(0)).toBe('0');
  });
});

describe('averageTokens 场均', () => {
  it('正常取整除', () => {
    expect(averageTokens(3000, 4)).toBe(750);
  });

  it('会话数为零时给 0 而非 NaN', () => {
    expect(averageTokens(3000, 0)).toBe(0);
  });
});
