// 会话状态机：CREATED → IN_PROGRESS → ENDED → REPORTED（接口文档第 2 节）
export type SessionStatus = 'CREATED' | 'IN_PROGRESS' | 'ENDED' | 'REPORTED';

// 历史会话条目（接口文档第 3 节 InterviewSummary）
export interface InterviewSummary {
  sessionId: string;
  status: SessionStatus;
  direction: string;
  difficulty: string;
  createTime: string;
}
