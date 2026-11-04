import { apiFetch } from './http';

// 用量统计与管理端 API（接口口径见后端 StatsController）：
// token 按消息口径聚合；管理页签由 /admin/overview 探测（404 即无权限/未开启，前端隐藏）

export interface TokenSummaryView {
  inputTokens: number;
  outputTokens: number;
  totalTokens: number;
  sessionCount: number;
  messageCount: number;
}

export interface DailyPoint {
  date: string; // yyyy-MM-dd
  tokens: number;
}

export interface SessionRow {
  sessionId: string;
  direction: string;
  difficulty: string;
  createdAt: string;
  messages: number;
  tokens: number;
}

export interface UserRow {
  username: string;
  sessions: number;
  tokens: number;
}

export interface TokenStatsResponse {
  summary: TokenSummaryView;
  daily: DailyPoint[];
  sessions: SessionRow[];
}

export interface AdminOverviewResponse {
  site: TokenSummaryView;
  users: UserRow[];
}

export interface BankUploadResponse {
  parsed: number;
  imported: number;
  skipped: number;
}

export function getMyStats() {
  return apiFetch<TokenStatsResponse>('/api/stats/tokens');
}

export function getAdminOverview() {
  return apiFetch<AdminOverviewResponse>('/api/stats/admin/overview');
}

/** 题库上传：Markdown 文件，幂等导入（题号已存在跳过）。 */
export function uploadQuestionBank(file: File) {
  const form = new FormData();
  form.append('file', file);
  return apiFetch<BankUploadResponse>('/api/stats/admin/question-bank', {
    method: 'POST',
    body: form,
  });
}
