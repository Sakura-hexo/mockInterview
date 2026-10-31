// 面试阶段推导纯函数（前端架构方案第 5 节）
// 从消息列表推导 UI 状态：当前题号、气泡徽章、流式状态；
// 组件只消费推导结果不做判断

import type { ChatMessage, MessageBadge } from '../types';

export interface AnnotatedMessage extends ChatMessage {
  badge: MessageBadge | null;
  questionNo: number | null; // 该消息所属题号（候选人消息为 null）
}

export interface InterviewPhase {
  questionNo: number; // 最近一条面试官消息所属题号
  streaming: boolean; // 有消息仍在流式输出（含 done 缺失的中间态）
  progressText: string; // 顶栏进度文案
}

// 为每条消息标注徽章与题号：QUESTION/NEXT 各推进一题，FOLLOW_UP 保持当前题。
// 徽章文案按 V1 定稿设计稿：第 N 题（蓝）、追问（黄）、第 N 题（紫，换题）
export function annotateMessages(messages: ChatMessage[]): AnnotatedMessage[] {
  let q = 0;
  return messages.map((m) => {
    if (m.role !== 'INTERVIEWER') return { ...m, badge: null, questionNo: null };
    let badge: MessageBadge;
    if (m.action === 'FOLLOW_UP') {
      badge = { text: '追问', cls: 'followup' };
    } else if (m.action === 'NEXT') {
      q += 1;
      badge = { text: `第 ${q} 题`, cls: 'next' };
    } else {
      // QUESTION 或无 action 的历史消息（契约字段缺失时的兜底）按新题推进
      q += 1;
      badge = { text: `第 ${q} 题`, cls: 'question' };
    }
    return { ...m, badge, questionNo: q };
  });
}

export function derivePhase(
  annotated: AnnotatedMessage[],
  totalQuestions?: number,
): InterviewPhase {
  const lastInterviewer = [...annotated].reverse().find((m) => m.role === 'INTERVIEWER');
  const questionNo = lastInterviewer?.questionNo ?? 0;
  const streaming = annotated.some((m) => m.streaming);
  return {
    questionNo,
    streaming,
    progressText: totalQuestions
      ? `第 ${questionNo}/${totalQuestions} 题`
      : questionNo > 0
        ? `第 ${questionNo} 题`
        : '准备中',
  };
}
