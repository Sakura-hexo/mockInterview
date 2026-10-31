// 报告轮询节奏与阶段推导纯函数（前端架构方案第 6 节）
// 轮询 3 秒起步，连续 5 次后退避到 10 秒封顶；分步进度由轮询次数推导，P0 不依赖后端真实阶段字段

export const REPORT_STAGES = ['面试消息汇总', '逐题评分', '生成改进建议', '汇总输出'] as const;

// 已完成的 202 轮询次数 → 下一次轮询间隔（ms）
export function nextPollDelay(generatingPolls: number): number {
  return generatingPolls < 5 ? 3000 : 10000;
}

// 已完成的 202 轮询次数 → 当前所处阶段下标（0-3）
// 每 2 次轮询推进一个阶段：3s×2 后进入逐题评分，再 6s 生成建议……封顶在"汇总输出"
export function deriveStage(generatingPolls: number): number {
  return Math.min(Math.floor(generatingPolls / 2), REPORT_STAGES.length - 1);
}

// 毫秒 → "N 分 N 秒"（报告页元信息格式，V1 设计稿 05）
export function formatDuration(ms: number): string {
  const totalSec = Math.max(0, Math.round(ms / 1000));
  const min = Math.floor(totalSec / 60);
  const sec = totalSec % 60;
  return `${min} 分 ${sec} 秒`;
}
