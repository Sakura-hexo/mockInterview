import { useEffect, useRef } from 'react';
import * as echarts from 'echarts';
import { buildUsageChartOption } from '../lib/deepseekChart';

interface UsageChartProps {
  dates: string[];
  values: number[];
  height?: number;
}

/**
 * DeepSeek 用量页同款图表（方案 plans/2026-11-04/DeepSeek风格用量图表-优化方案.md）：
 * SVG 渲染、初始无动画、tooltip 首显即时后续 0.4s 渐显；容器高默认 272 与其用量页一致。
 */
export default function UsageChart({ dates, values, height = 272 }: UsageChartProps) {
  const containerRef = useRef<HTMLDivElement>(null);
  const chartRef = useRef<echarts.ECharts | null>(null);

  useEffect(() => {
    const el = containerRef.current;
    if (!el) return;
    const chart = echarts.init(el, null, { renderer: 'svg' });
    chartRef.current = chart;

    // 原 showtip/hidetip 技巧：第一次显示即时，之后的显示走 0.4s 渐显，隐藏始终即时
    let firstCycle = false;
    chart.on('hidetip', () => {
      if (!firstCycle) {
        firstCycle = true;
        chart.setOption({ tooltip: { transitionDuration: 0 } });
      }
    });
    chart.on('showtip', () => {
      if (firstCycle) {
        firstCycle = false;
        chart.setOption({ tooltip: { transitionDuration: 0.4 } });
      }
    });

    const observer = new ResizeObserver(() => chart.resize());
    observer.observe(el);
    return () => {
      observer.disconnect();
      chart.dispose();
      chartRef.current = null;
    };
  }, []);

  useEffect(() => {
    const chart = chartRef.current;
    if (!chart) return;
    chart.setOption(
      buildUsageChartOption({
        dates,
        values,
        convertToPixel: (dataIndex) => chart.convertToPixel({ xAxisIndex: 0 }, dataIndex),
      }),
    );
  }, [dates, values]);

  return <div ref={containerRef} style={{ height }} />;
}
