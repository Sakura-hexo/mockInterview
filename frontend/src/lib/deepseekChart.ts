import type { EChartsOption } from 'echarts';

// DeepSeek 开放平台用量图的移植（方案 plans/2026-11-04/DeepSeek风格用量图表-优化方案.md）：
// 以下常量与算法逐条对应其 bundle 4099.js 压缩源码，见方案文档的逆向规格一节

const NICE_STEPS = [1, 1.2, 1.4, 1.6, 1.8, 2, 2.2, 2.5, 3, 3.5, 4, 4.5, 5, 6, 7, 8, 9, 10];

/** DeepSeek 模型分组色板（YlOrRd），单序列取首个 #FD8D3C。 */
export const MODEL_COLORS = [
  '#FD8D3C', '#FED976', '#800026', '#FC4E2A', '#FFFFCC',
  '#FF301A', '#FEB24C', '#E31A1C', '#FFEDA0', '#B10026',
];

export const AXIS_LINE_COLOR = '#D2D8E5';
export const AXIS_LABEL_COLOR = 'rgba(2, 14, 54, 0.6)';
export const BAR_BORDER_RADIUS = 4;
export const BAR_MIN_HEIGHT = 4;
export const BAR_MIN_WIDTH = 4;
export const BAR_MAX_WIDTH = 120;

/** y 轴刻度（原函数 p_）：数据最大值除以 1.7 归一到 nice 表，interval 乘回量级，max 恒为两段。 */
export function yAxisTicks(dataMax: number): { max: number; interval: number; ticks: number[] } {
  if (!(dataMax > 0)) {
    return { max: 2, interval: 2, ticks: [2] };
  }
  let n = dataMax / 1.7;
  let exponent = Math.floor(Math.log(n) / Math.LN10);
  if (n / Math.pow(10, exponent) >= 10) {
    exponent += 1;
  }
  const magnitude = Math.pow(10, exponent);
  const normalized = Number((n / magnitude).toPrecision(12));
  const nice = NICE_STEPS.find((v) => v >= normalized) ?? 10;
  const decimals = Math.min(Math.max(-exponent + 2, 0), 20);
  const interval = +(nice * magnitude).toFixed(decimals);
  const ticks = [interval, +(2 * interval).toFixed(decimals)];
  return { max: ticks[1], interval, ticks };
}

/** x 轴稀疏刻度下标（原函数 pE）：≤3 个全显，≤7 个显 3 个，其余一律 4 个等距取整。 */
export function xTickIndexes(count: number): Set<number> {
  if (count <= 0) return new Set();
  const total = count <= 3 ? count : count <= 7 ? 3 : 4;
  if (total === 1) return new Set([0]);
  const indexes = new Set<number>();
  for (let i = 0; i < total; i += 1) {
    indexes.add(Math.round((i * (count - 1)) / (total - 1)));
  }
  return indexes;
}

/** y 轴标签格式（原函数 ud）：千分位。 */
export function formatYLabel(value: number): string {
  return value.toLocaleString('en-US');
}

/** y 轴标签像素宽（原函数 pT ∘ px）：22 基数，第 3 个字符起每个 7.5px。 */
export function yAxisLabelWidth(ticks: number[]): number {
  const maxLen = Math.max(...ticks.map((t) => formatYLabel(t).length), 0);
  return 22 + (maxLen < 3 ? 0 : (maxLen - 2) * 7.5);
}

/** tooltip 行（原模板）：12x12 色点 + 左标签右值。 */
export function tooltipHtml(date: string, label: string, value: number, color: string): string {
  const row = (text: string, amount: string) =>
    `<div style="display: flex; justify-content: space-between; align-items: center; gap: 24px; min-width: 0;">` +
    `<div style="display: flex; align-items: center;"><div style="width: 12px; height: 12px; background: ${color}; margin-right: 8px; border-radius: 2px;"></div>${text}</div>` +
    `<div>${amount}</div></div>`;
  return (
    `<div style="font-weight: 600; margin-bottom: 6px;">${date}</div>` +
    row(label, formatYLabel(value))
  );
}

// DeepSeek 原版 tooltip 卡片样式（pI），CSS 变量换成项目内具体值
export const TOOLTIP_EXTRA_CSS =
  'box-sizing: border-box; padding: 12px 24px; min-width: 200px; background-color: #ffffff; ' +
  'border: 1px solid #d5dbe6; border-radius: 24px; box-shadow: 0 8px 24px rgba(2, 14, 54, 0.14); ' +
  'font-family: inherit;';

export interface UsageChartInputs {
  dates: string[];
  values: number[];
  seriesLabel?: string;
  /** tooltip 锚点换算：悬停柱的像素 x，组件侧接 chart.convertToPixel。 */
  convertToPixel?: (dataIndex: number) => number | null | undefined;
}

type RenderApi = {
  value: (dim: number) => unknown;
  coord: (point: [unknown, number]) => [number, number];
  size?: (band: [number, number]) => unknown;
};

type PositionSize = { viewSize: [number, number]; contentSize: [number, number] };

function clampBarWidth(width: number): number {
  return Math.min(BAR_MAX_WIDTH, Math.max(BAR_MIN_WIDTH, width));
}

/** 原 renderItem：柱宽 0.72 带宽按组数分摊（单组即 0.72 带宽），顶部圆角，竖向渐变填色（单段即纯色）。 */
function renderRoundedBar(barColor: string) {
  return (_params: unknown, api: RenderApi) => {
    const category = api.value(0);
    const value = Number(api.value(3));
    if (value <= 0) {
      return { type: 'group', children: [] };
    }
    const top = api.coord([category, value]);
    const bottom = api.coord([category, 0]);
    const barHeight = Math.max(bottom[1] - top[1], BAR_MIN_HEIGHT);
    const band = api.size?.([1, 0]);
    const bandWidth = Array.isArray(band) ? band[0] : (band ?? 0);
    const groupIdx = Number(api.value(2));
    const groupCount = 1;
    const barWidth = clampBarWidth((0.72 * bandWidth) / (groupCount + 0.3 * (groupCount - 1)));
    const x =
      top[0] - (barWidth * groupCount + 0.3 * barWidth * (groupCount - 1)) / 2 + groupIdx * barWidth * 1.3;
    return {
      type: 'rect',
      shape: { x, y: bottom[1] - barHeight, width: barWidth, height: barHeight, r: [4, 4, 0, 0] },
      transition: ['shape', 'style'],
      style: {
        fill: {
          type: 'linear',
          x: 0,
          y: 0,
          x2: 0,
          y2: 1,
          colorStops: [
            { offset: 0, color: barColor },
            { offset: 1, color: barColor },
          ],
        },
      },
    };
  };
}

/** 原 option 构造 B()：本页单序列场景，结构与 DeepSeek 用量图逐字段一致。 */
export function buildUsageChartOption(inputs: UsageChartInputs): EChartsOption {
  const { dates, values, seriesLabel = '消耗', convertToPixel } = inputs;
  const barColor = MODEL_COLORS[0];
  const ticks = yAxisTicks(Math.max(0, ...values, 0));
  const labelWidth = yAxisLabelWidth(ticks.ticks);
  const tickSet = xTickIndexes(dates.length);
  const chartInnerRight = 12;

  const position = (
    point: [number, number],
    params: unknown,
    _dom: unknown,
    _rect: unknown,
    size: PositionSize,
  ): [number, number] => {
    const { viewSize, contentSize } = size;
    const innerWidth = viewSize[0] - labelWidth - chartInnerRight;
    const groupCount = 1;
    const barWidth = clampBarWidth((innerWidth / Math.max(1, dates.length)) * 0.72 / (groupCount + 0.3 * (groupCount - 1)));
    const groupHalf = (barWidth * groupCount + 0.3 * barWidth * (groupCount - 1)) / 2;
    const dataIndex = Array.isArray(params) ? (params[0] as { dataIndex?: number })?.dataIndex : undefined;
    const anchorPx = dataIndex !== undefined ? convertToPixel?.(dataIndex) : undefined;
    const anchor = anchorPx != null ? anchorPx : point[0];
    const contentW = Math.min(contentSize[0], viewSize[0] - 16);
    const contentH = Math.min(contentSize[1], viewSize[1] - 16);
    let x = anchor + groupHalf;
    if (x + contentW + 8 > viewSize[0]) {
      x = anchor - contentW - groupHalf;
    }
    x = Math.min(Math.max(x, 8), Math.max(8, viewSize[0] - contentW - 8));
    const bottomLimit = viewSize[1] - 24;
    const y = Math.max(Math.min((viewSize[1] - contentH) / 2, bottomLimit - 16 - contentH), 0);
    return [x, y];
  };

  const formatter = (params: unknown): string => {
    const list = Array.isArray(params) ? params : [params];
    const dataIndex = (list[0] as { dataIndex?: number })?.dataIndex ?? 0;
    return tooltipHtml(dates[dataIndex] ?? '', seriesLabel, values[dataIndex] ?? 0, barColor);
  };

  const seriesData = dates.map((date, i) => [date, i, 0, values[i]]);

  // renderItem 与 position 的回调签名按 echarts 运行时契约移植，经一次断言接入官方类型
  const option = {
    animation: false,
    animationDuration: 0,
    animationDurationUpdate: 300,
    animationEasingUpdate: 'cubicOut',
    textStyle: { fontFamily: 'inherit' },
    grid: { left: labelWidth, right: chartInnerRight, top: 8, bottom: 24 },
    tooltip: {
      show: true,
      confine: true,
      enterable: true,
      transitionDuration: 0,
      trigger: 'axis',
      formatter,
      extraCssText: TOOLTIP_EXTRA_CSS,
      position,
      axisPointer: { lineStyle: { color: AXIS_LINE_COLOR } },
    },
    xAxis: {
      type: 'category',
      data: dates,
      axisTick: { show: false },
      axisLabel: {
        color: AXIS_LABEL_COLOR,
        interval: (index: number) => tickSet.has(index),
      },
      axisLine: { lineStyle: { color: AXIS_LINE_COLOR } },
    },
    yAxis: {
      type: 'value',
      min: 0,
      max: ticks.max,
      interval: ticks.interval,
      splitLine: { lineStyle: { color: AXIS_LINE_COLOR } },
      axisLabel: {
        color: AXIS_LABEL_COLOR,
        margin: labelWidth,
        align: 'left',
        formatter: (value: number) => formatYLabel(value),
      },
    },
    series: [
      {
        id: 'bar-0',
        data: values,
        type: 'bar',
        smooth: true,
        showSymbol: false,
        itemStyle: { color: barColor, opacity: 0 },
        emphasis: { disabled: true },
      },
      {
        id: 'roundedBars',
        type: 'custom',
        coordinateSystem: 'cartesian2d',
        encode: { x: 0, y: 3 },
        data: seriesData,
        renderItem: renderRoundedBar(barColor),
        silent: true,
        tooltip: { show: false },
        z: 2,
      },
    ],
  } as unknown as EChartsOption;
  return option;
}
