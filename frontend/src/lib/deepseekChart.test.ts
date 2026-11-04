import { describe, expect, it } from 'vitest';
import {
  buildUsageChartOption,
  formatYLabel,
  tooltipHtml,
  xTickIndexes,
  yAxisLabelWidth,
  yAxisTicks,
} from './deepseekChart';

// DeepSeek 用量图移植测试：算法输出与其 bundle 内压缩函数逐值对齐（方案文档逆向规格）

describe('yAxisTicks 两段刻度', () => {
  it('数据最大值归一到 nice 表，max 恒为两段', () => {
    expect(yAxisTicks(100)).toEqual({ max: 120, interval: 60, ticks: [60, 120] });
    expect(yAxisTicks(4337)).toEqual({ max: 6000, interval: 3000, ticks: [3000, 6000] });
  });

  it('零与极小值不退化', () => {
    expect(yAxisTicks(0)).toEqual({ max: 2, interval: 2, ticks: [2] });
    expect(yAxisTicks(1)).toEqual({ max: 1.2, interval: 0.6, ticks: [0.6, 1.2] });
  });
});

describe('xTickIndexes 稀疏刻度', () => {
  it('30 天只显 4 个且首尾必显（对齐原算法的取整结果）', () => {
    expect([...xTickIndexes(30)]).toEqual([0, 10, 19, 29]);
  });

  it('短序列逐步降级', () => {
    expect([...xTickIndexes(7)]).toEqual([0, 3, 6]);
    expect([...xTickIndexes(3)]).toEqual([0, 1, 2]);
    expect([...xTickIndexes(1)]).toEqual([0]);
    expect([...xTickIndexes(0)]).toEqual([]);
  });
});

describe('y 轴标签', () => {
  it('格式为千分位', () => {
    expect(formatYLabel(6000)).toBe('6,000');
  });

  it('宽度按 22 基数加每字符 7.5', () => {
    expect(yAxisLabelWidth([60, 120])).toBe(29.5);
    expect(yAxisLabelWidth([3000, 6000])).toBe(44.5);
  });
});

describe('tooltipHtml', () => {
  it('含日期、色点与千分位值', () => {
    const html = tooltipHtml('2026-11-03', '消耗', 4337, '#FD8D3C');
    expect(html).toContain('2026-11-03');
    expect(html).toContain('4,337');
    expect(html).toContain('#FD8D3C');
  });
});

describe('buildUsageChartOption 移植冒烟', () => {
  const dates = ['2026-11-01', '2026-11-02', '2026-11-03'];
  const values = [100, 0, 50];
  // 断言一次松型访问：option 结构由冒烟断言覆盖，官方 EChartsOption 的函数签名在组件接线处
  const option = buildUsageChartOption({ dates, values }) as unknown as Record<
    string,
    Record<string, unknown>
  >;

  it('动画与网格与 DeepSeek 规格一致', () => {
    expect(option.animation).toBe(false);
    expect(option.animationDurationUpdate).toBe(300);
    expect(option.grid).toMatchObject({ left: 29.5, right: 12, top: 8, bottom: 24 });
    expect(option.yAxis).toMatchObject({ min: 0, max: 120, interval: 60 });
  });

  it('双层 series：透明 bar 供数据定位，custom 画圆角柱', () => {
    const series = option.series as unknown as Record<string, unknown>[];
    expect(series).toHaveLength(2);
    expect(series[0]).toMatchObject({ id: 'bar-0', type: 'bar' });
    expect((series[0].itemStyle as Record<string, unknown>).opacity).toBe(0);
    expect(series[1]).toMatchObject({ id: 'roundedBars', type: 'custom', z: 2 });
  });

  it('x 轴为分类日期且刻度按稀疏算法', () => {
    expect(option.xAxis).toMatchObject({ type: 'category', data: dates });
    const manyDates = Array.from({ length: 30 }, (_, i) => `2026-10-${String(i + 1).padStart(2, '0')}`);
    const many = buildUsageChartOption({ dates: manyDates, values: manyDates.map(() => 1) }) as unknown as Record<
      string,
      Record<string, unknown>
    >;
    const interval = (many.xAxis.axisLabel as Record<string, unknown>).interval as (
      index: number,
    ) => boolean;
    expect(interval(0)).toBe(true);
    expect(interval(1)).toBe(false);
    expect(interval(10)).toBe(true);
    expect(interval(29)).toBe(true);
  });

  it('tooltip formatter 输出悬停日的日期与值', () => {
    const tooltip = option.tooltip as Record<string, unknown>;
    const formatter = tooltip.formatter as (params: { dataIndex: number }[]) => string;
    expect(formatter([{ dataIndex: 2 }])).toContain('2026-11-03');
    expect(formatter([{ dataIndex: 2 }])).toContain('50');
  });
});
