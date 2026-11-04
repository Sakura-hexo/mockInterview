import type { DailyPoint } from '../api/stats';

// 统计页的纯展示逻辑：趋势补零、token 缩写——纯函数便于单测
// 图表绘制（刻度/柱形/tooltip）在 deepseekChart.ts

/** 近 N 天趋势补零：后端只返回有数据的天，前端铺满连续日期（含今天，本地时区）。 */
export function fillDailyTrend(points: DailyPoint[], days: number): DailyPoint[] {
  const byDate = new Map(points.map((p) => [p.date, p.tokens]));
  const filled: DailyPoint[] = [];
  const today = new Date();
  for (let offset = days - 1; offset >= 0; offset -= 1) {
    const day = new Date(today);
    day.setDate(today.getDate() - offset);
    const key = localDateKey(day);
    filled.push({ date: key, tokens: byDate.get(key) ?? 0 });
  }
  return filled;
}

/** 本地时区的 yyyy-MM-dd（toISOString 会按 UTC 切天，跨零点会差一天）。 */
export function localDateKey(day: Date): string {
  const month = String(day.getMonth() + 1).padStart(2, '0');
  const date = String(day.getDate()).padStart(2, '0');
  return `${day.getFullYear()}-${month}-${date}`;
}

// 柱状图绘制与刻度算法已迁移 deepseekChart.ts（DeepSeek 用量图移植，2026-11-04）

/** token 缩写：万位以上一位小数，其余原样。 */
export function formatTokens(value: number): string {
  if (value >= 10000) {
    return `${(value / 10000).toFixed(1)}万`;
  }
  return String(value);
}

/** 场均 token：会话数为 0 时给 0，不用 NaN 污染展示。 */
export function averageTokens(totalTokens: number, sessionCount: number): number {
  if (sessionCount <= 0) return 0;
  return Math.round(totalTokens / sessionCount);
}
