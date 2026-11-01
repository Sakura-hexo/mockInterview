import { useCallback, useEffect, useMemo, useRef, useState } from 'react';
import { getInterviewDetail } from '../api/interview';
import { streamChat } from '../api/interviewStream';
import { annotateMessages, derivePhase } from '../lib/interviewPhase';
import type { ChatMessage, InterviewSummary } from '../types';

// 聊天页核心 hook（前端架构方案第 7 节）：
// 发送、SSE 订阅与清理、停止、刷新恢复轮询、消息列表维护

const STREAMING_ID = '__streaming__';
const RECOVERY_POLL_MS = 3000; // 恢复轮询间隔（架构方案 4.3）

export interface ActivityItem {
  id: number;
  who: string;
  text: string;
  state: 'running' | 'ok' | 'failed';
}

// 详情消息的 role 归一（接口文档未定义枚举值，兼容 ASSISTANT/USER 等写法）
function normalizeRole(role: string): 'INTERVIEWER' | 'CANDIDATE' {
  const up = (role || '').toUpperCase();
  return up === 'CANDIDATE' || up === 'USER' ? 'CANDIDATE' : 'INTERVIEWER';
}

export function useInterviewStream(sessionId: string) {
  const [messages, setMessages] = useState<ChatMessage[]>([]);
  const [session, setSession] = useState<(InterviewSummary & { questionCount?: number }) | null>(null);
  const [streaming, setStreaming] = useState(false);
  const [streamStart, setStreamStart] = useState(0); // 流式起始时间戳，提示条显示用时
  const [streamNow, setStreamNow] = useState(0); // 每秒跳动的时间戳：无增量到达时提示条也能走秒
  const [recovering, setRecovering] = useState(false);
  const [loaded, setLoaded] = useState(false);
  const [loadError, setLoadError] = useState('');
  const [activities, setActivities] = useState<ActivityItem[]>([]);
  const abortRef = useRef<AbortController | null>(null);
  const completedRef = useRef(false); // 是否已收到 done 或 error 终态事件
  const pollTimer = useRef<ReturnType<typeof setTimeout> | null>(null);
  const aliveRef = useRef(true);
  const actIdRef = useRef(0);

  // 流式期间每秒强制重渲染：等待首字或工具检索阶段没有增量事件，
  // 提示条的计时不能冻结（实测秒数停在 0 会被误认为死机）
  useEffect(() => {
    if (!streaming) return;
    setStreamNow(Date.now());
    const timer = setInterval(() => setStreamNow(Date.now()), 1000);
    return () => clearInterval(timer);
  }, [streaming]);

  const pushActivity = useCallback((who: string, text: string, state: ActivityItem['state']) => {
    setActivities((prev) => [
      ...prev.slice(-3), // 活动流保留最近 4 条（页面方案 4.2）
      { id: ++actIdRef.current, who, text, state },
    ]);
  }, []);

  // 卸载清理：中断流、停轮询
  useEffect(() => {
    aliveRef.current = true;
    return () => {
      aliveRef.current = false;
      abortRef.current?.abort();
      if (pollTimer.current) clearTimeout(pollTimer.current);
    };
  }, []);

  const applyDetail = useCallback(async () => {
    const d = await getInterviewDetail(sessionId);
    if (!aliveRef.current) return [];
    setSession(d.session);
    const normalized: ChatMessage[] = d.messages.map((m, i) => ({
      id: `detail-${i}`,
      role: normalizeRole(m.role),
      content: m.content,
      tokens: m.tokens,
      time: m.time,
      action: m.action,
    }));
    setMessages(normalized);
    return normalized;
  }, [sessionId]);

  const stopRecovery = useCallback(() => {
    if (pollTimer.current) clearTimeout(pollTimer.current);
    pollTimer.current = null;
    setRecovering(false);
  }, []);

  // 刷新恢复（架构方案 4.3，用户已确认）：
  // 最后一条是候选人 → 该轮已发出、面试官回复未落库 → 降级为 3 秒轮询，出现新面试官消息即停止
  const startRecovery = useCallback(() => {
    setRecovering(true);
    const tick = async () => {
      try {
        const normalized = await applyDetail();
        const last = normalized.at(-1);
        if (last && last.role === 'INTERVIEWER') {
          if (aliveRef.current) {
            setRecovering(false);
            window.dispatchEvent(new Event('interviews-changed'));
          }
          return;
        }
      } catch {
        // 单次失败忽略，下轮重试
      }
      if (aliveRef.current) {
        pollTimer.current = setTimeout(tick, RECOVERY_POLL_MS);
      }
    };
    pollTimer.current = setTimeout(tick, RECOVERY_POLL_MS);
  }, [applyDetail]);

  useEffect(() => {
    (async () => {
      try {
        const normalized = await applyDetail();
        const last = normalized.at(-1);
        if (last && last.role === 'CANDIDATE') {
          startRecovery();
          pushActivity('interviewer', '等待面试官回复（自动同步中）', 'running');
        } else {
          // 幂等重置：StrictMode 双挂载或恢复中往返导航时，清掉残留的轮询状态
          stopRecovery();
        }
      } catch {
        if (aliveRef.current) setLoadError('会话加载失败，请刷新重试');
      } finally {
        if (aliveRef.current) setLoaded(true);
      }
    })();
  }, [applyDetail, startRecovery, stopRecovery, pushActivity]);

  const patchStreaming = useCallback((patch: (m: ChatMessage) => ChatMessage) => {
    setMessages((prev) => prev.map((m) => (m.id === STREAMING_ID ? patch(m) : m)));
  }, []);

  const send = useCallback(
    async (content: string) => {
      const text = content.trim();
      if (!text || streaming) return;
      stopRecovery();
      completedRef.current = false;
      setMessages((prev) => [
        ...prev,
        { id: `me-${Date.now()}`, role: 'CANDIDATE', content: text },
        { id: STREAMING_ID, role: 'INTERVIEWER', content: '', streaming: true },
      ]);
      setStreaming(true);
      setStreamStart(Date.now());
      setStreamNow(Date.now());
      // 活动流按轮次重置：上一轮的终态条目留到本轮，会叠加成"重复且状态不变"的观感
      setActivities([]);
      pushActivity('interviewer', '生成回复中…', 'running');

      const ctl = new AbortController();
      abortRef.current = ctl;
      const completeTurn = () => {
        pushActivity('interviewer', '回复完成', 'ok');
        window.dispatchEvent(new Event('interviews-changed')); // 通知侧栏刷新（页面方案 4.2 done 事件）
      };
      try {
        await streamChat(
          sessionId,
          text,
          {
            onDelta: (delta) => patchStreaming((m) => ({ ...m, content: m.content + delta })),
            onAction: (type) => patchStreaming((m) => ({ ...m, action: type })),
            onMetrics: (metrics) =>
              patchStreaming((m) => ({ ...m, tokens: metrics.outputTokens, latencyMs: metrics.latencyMs })),
            // 终态事件一到就主动断开读取：清理不依赖后端 emitter 与代理的连接关闭传播
            onDone: () => {
              completedRef.current = true;
              ctl.abort();
            },
            onErrorEvent: (message) => {
              completedRef.current = true;
              patchStreaming((m) => ({ ...m, error: message }));
              pushActivity('interviewer', '生成失败', 'failed');
              ctl.abort();
            },
          },
          ctl.signal,
        );
        if (completedRef.current) {
          completeTurn();
        } else {
          // 流关闭但终态事件缺失：后端可能仍在生成，转恢复轮询等落库
          pushActivity('interviewer', '连接中断，自动同步中', 'failed');
          startRecovery();
        }
      } catch (err) {
        if (err instanceof DOMException && err.name === 'AbortError') {
          if (completedRef.current) {
            completeTurn(); // onDone 主动 abort 的正常路径
          } else {
            // 用户主动停止：保留已生成部分，后端仍会落库，轮询自动同步
            pushActivity('interviewer', '已停止显示，回复完成后自动同步', 'failed');
            startRecovery();
          }
        } else {
          patchStreaming((m) => ({
            ...m,
            error: err instanceof Error ? err.message : '网络异常',
          }));
          pushActivity('interviewer', '连接异常，自动同步中', 'failed');
          startRecovery();
        }
      } finally {
        // 去光标，并换掉流式专用 id：否则下一轮的补丁会命中历史消息
        const finalId = `iv-${Date.now()}`;
        patchStreaming((m) => ({ ...m, id: finalId, streaming: false }));
        if (aliveRef.current) {
          setStreaming(false);
          setStreamStart(0);
        }
        abortRef.current = null;
      }
    },
    [sessionId, streaming, patchStreaming, pushActivity, stopRecovery, startRecovery],
  );

  const stop = useCallback(() => {
    abortRef.current?.abort();
  }, []);

  const annotated = useMemo(() => annotateMessages(messages), [messages]);
  const phase = useMemo(() => derivePhase(annotated, session?.questionCount), [annotated, session]);

  return {
    session,
    annotated,
    phase,
    streaming,
    streamStart,
    streamNow,
    recovering,
    loaded,
    loadError,
    activities,
    send,
    stop,
  };
}
