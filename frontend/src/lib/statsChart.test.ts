import { describe, expect, it } from 'vitest';
import {
  averageTokens,
  barPercents,
  fillDailyTrend,
  formatTokens,
  localDateKey,
  shortDate,
  trendTickIndexes,
} from './statsChart';

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

describe('barPercents 条形高度', () => {
  it('按最大值归一化为百分比', () => {
    expect(barPercents([100, 50, 0])).toEqual([100, 50, 2]);
  });

  it('有值的天保底 4，零值天 2，全零时等高矮条', () => {
    expect(barPercents([100, 1, 0])).toEqual([100, 4, 2]);
    expect(barPercents([0, 0])).toEqual([4, 4]);
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

describe('trendTickIndexes 柱底刻度', () => {
  it('每 5 根一标且末尾必标', () => {
    expect(trendTickIndexes(30)).toEqual([0, 5, 10, 15, 20, 25, 29]);
  });

  it('短序列与空序列不重复不越界', () => {
    expect(trendTickIndexes(1)).toEqual([0]);
    expect(trendTickIndexes(3)).toEqual([0, 2]);
    expect(trendTickIndexes(0)).toEqual([]);
  });
});

describe('shortDate 短日期', () => {
  it('yyyy-MM-dd 取 MM-DD', () => {
    expect(shortDate('2026-11-04')).toBe('11-04');
    expect(shortDate('11-4')).toBe('11-4');
  });
});
