import { describe, expect, it } from 'vitest';
import { annotateMessages, derivePhase } from './interviewPhase';
import type { ChatMessage } from '../types';

const iv = (action: ChatMessage['action'], id: string): ChatMessage => ({
  id,
  role: 'INTERVIEWER',
  content: 'c',
  action,
});
const me = (id: string): ChatMessage => ({ id, role: 'CANDIDATE', content: 'a' });

describe('annotateMessages 徽章与题号', () => {
  it('QUESTION 首题、FOLLOW_UP 追问同题、NEXT 换题', () => {
    const r = annotateMessages([iv('QUESTION', '1'), me('2'), iv('FOLLOW_UP', '3'), me('4'), iv('NEXT', '5')]);
    expect(r.map((m) => m.badge?.text ?? null)).toEqual(['第 1 题', null, '追问', null, '第 2 题']);
    expect(r.map((m) => m.questionNo)).toEqual([1, null, 1, null, 2]);
    expect(r[2].badge?.cls).toBe('followup');
    expect(r[4].badge?.cls).toBe('next');
  });

  it('多轮追问保持题号，连续换题正确递增', () => {
    const r = annotateMessages([iv('QUESTION', 'a'), iv('FOLLOW_UP', 'b'), iv('NEXT', 'c'), iv('FOLLOW_UP', 'd'), iv('NEXT', 'e')]);
    expect(r.map((m) => m.questionNo)).toEqual([1, 1, 2, 2, 3]);
  });

  it('无 action 的历史消息按新题推进（契约兜底）', () => {
    const r = annotateMessages([iv(undefined, '1'), me('2'), iv(undefined, '3')]);
    expect(r.map((m) => m.questionNo)).toEqual([1, null, 2]);
    expect(r[0].badge?.cls).toBe('question');
  });
});

describe('derivePhase 顶栏推导', () => {
  it('有总数时显示 第 N/M 题', () => {
    const r = annotateMessages([iv('QUESTION', '1'), iv('NEXT', '2')]);
    expect(derivePhase(r, 10).progressText).toBe('第 2/10 题');
  });

  it('无总数时只显示 第 N 题，空会话显示 准备中', () => {
    expect(derivePhase(annotateMessages([iv('QUESTION', '1')])).progressText).toBe('第 1 题');
    expect(derivePhase(annotateMessages([])).progressText).toBe('准备中');
  });

  it('streaming 检测：任意消息处于流式中即为 true（done 缺失容错）', () => {
    const msgs: ChatMessage[] = [
      { id: 's', role: 'INTERVIEWER', content: '生成中', streaming: true },
    ];
    expect(derivePhase(annotateMessages(msgs)).streaming).toBe(true);
    expect(derivePhase(annotateMessages([iv('QUESTION', '1')])).streaming).toBe(false);
  });
});
