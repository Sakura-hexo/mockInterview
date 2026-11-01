import { apiFetch } from './http';
import type { ChatMessage, InterviewReport, InterviewSummary, SessionConfig } from '../types';

// 领域方法与接口文档一一对应（前端架构方案第 3 节），随 F 步骤逐个补充
// 已有：F1 listInterviews、F3 createInterview/startInterview、F4 endInterview/getInterviewDetail

// 接口文档 4.6 历史会话列表
export function listInterviews(page = 1, size = 20) {
  return apiFetch<{ total: number; list: InterviewSummary[] }>(
    `/api/interviews?page=${page}&size=${size}`,
  );
}

// 接口文档 4.1 新建面试：resumeText 与 resumeFile 二选一
export interface CreateInterviewInput {
  resumeText?: string;
  resumeFile?: File;
  jdText: string;
  config: SessionConfig;
}

export function createInterview(input: CreateInterviewInput) {
  if (input.resumeFile) {
    const form = new FormData();
    form.append('resumeFile', input.resumeFile);
    form.append('jdText', input.jdText);
    form.append('config', JSON.stringify(input.config));
    return apiFetch<{ sessionId: string; resumeDigest: string }>('/api/interviews', {
      method: 'POST',
      body: form,
    });
  }
  return apiFetch<{ sessionId: string; resumeDigest: string }>('/api/interviews', {
    method: 'POST',
    body: JSON.stringify({
      resumeText: input.resumeText ?? '',
      jdText: input.jdText,
      config: input.config,
    }),
  });
}

// 接口文档 4.2 开始面试：CREATED → IN_PROGRESS，返回第一题
export function startInterview(sessionId: string) {
  return apiFetch<{ firstQuestion: string; focusPoints: string[] }>(
    `/api/interviews/${sessionId}/start`,
    { method: 'POST' },
  );
}

// 接口文档 4.4 结束面试：幂等，触发异步复盘生成，状态变 ENDED
export function endInterview(sessionId: string) {
  return apiFetch<{ reportStatus: string }>(`/api/interviews/${sessionId}/end`, {
    method: 'POST',
  });
}

// 接口文档 4.5 获取复盘报告：生成中返回 code 202，完成返回完整 InterviewReport
export type ReportResult =
  | { state: 'GENERATING' }
  | { state: 'DONE'; report: InterviewReport };

export async function getReport(sessionId: string): Promise<ReportResult> {
  const data = await apiFetch<InterviewReport | { reportStatus: string }>(
    `/api/interviews/${sessionId}/report`,
    undefined,
    [202],
  );
  if (data && 'reportStatus' in data) return { state: 'GENERATING' };
  return { state: 'DONE', report: data as InterviewReport };
}
// 接口文档 4.7 会话详情：刷新恢复与消息记录的数据源
// questionCount 为可选字段：InterviewSummary 未定义该字段，后端补充后顶栏可显示 第 N/M 题
export function getInterviewDetail(sessionId: string) {
  return apiFetch<{
    session: InterviewSummary & { questionCount?: number };
    messages: ChatMessage[];
  }>(`/api/interviews/${sessionId}`);
}
