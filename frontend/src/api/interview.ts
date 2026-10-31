import { apiFetch } from './http';
import type { InterviewSummary } from '../types';

// 领域方法与接口文档一一对应（前端架构方案第 3 节），随 F 步骤逐个补充
// F1 仅实现登录验证所需的列表接口（接口文档 4.6）

export function listInterviews(page = 1, size = 20) {
  return apiFetch<{ total: number; list: InterviewSummary[] }>(
    `/api/interviews?page=${page}&size=${size}`,
  );
}
