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

// 面试配置（接口文档第 3 节 SessionConfig）
export interface SessionConfig {
  direction: string;
  difficulty: string;
  questionCount: number;
}

// SSE action 事件类型（接口文档 4.3）
export type InterviewAction = 'QUESTION' | 'FOLLOW_UP' | 'NEXT';

// 消息（接口文档第 3 节 ChatMessage，UI 层扩展流式中间态字段）
// 契约备注：action 字段接口文档未定义，前端假设后端在消息中回带，
// 否则历史消息无法还原题号与徽章（待接口文档 4.7 补充）
export interface ChatMessage {
  id?: string;
  role: 'INTERVIEWER' | 'CANDIDATE';
  content: string;
  tokens?: number;
  time?: string;
  action?: InterviewAction;
  latencyMs?: number;
  streaming?: boolean;
  error?: string;
}

// 消息徽章标注结果（lib/interviewPhase 推导）
export interface MessageBadge {
  text: string;
  cls: 'question' | 'followup' | 'next';
}
