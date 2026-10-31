// SSE 流客户端（前端架构方案 4.2、接口文档 4.3）
// fetch + ReadableStream 手写循环，不用 EventSource（带不了 Authorization 头）
// 事件顺序 message → action → metrics → done；异常时后端发 error 事件

import { ApiError, clearToken, getToken } from './http';
import { parseSse, type SseEvent } from '../lib/sseParser';
import type { InterviewAction } from '../types';

export interface StreamMetrics {
  inputTokens: number;
  outputTokens: number;
  latencyMs: number;
}

export interface StreamCallbacks {
  onDelta: (delta: string) => void;
  onAction: (type: InterviewAction) => void;
  onMetrics: (metrics: StreamMetrics) => void;
  onDone: (round: number) => void;
  onErrorEvent: (message: string) => void;
}

export async function streamChat(
  sessionId: string,
  content: string,
  cb: StreamCallbacks,
  signal?: AbortSignal,
): Promise<void> {
  const token = getToken();
  const res = await fetch(`/api/interviews/${sessionId}/chat`, {
    method: 'POST',
    headers: {
      'Content-Type': 'application/json',
      ...(token ? { Authorization: `Bearer ${token}` } : {}),
    },
    body: JSON.stringify({ content, images: [] }), // images 为 P1 预留（接口文档 4.3）
    signal,
  });

  if (res.status === 401) {
    clearToken();
    if (window.location.pathname !== '/login') window.location.assign('/login');
    throw new ApiError(401, '未认证');
  }
  if (!res.ok || !res.body) {
    throw new ApiError(res.status, `请求失败（${res.status}）`);
  }

  const reader = res.body.getReader();
  const decoder = new TextDecoder();
  let buffer = '';
  for (;;) {
    const { done, value } = await reader.read();
    if (done) break;
    buffer += decoder.decode(value, { stream: true });
    const { events, rest } = parseSse(buffer);
    buffer = rest;
    events.forEach((ev) => dispatch(ev, cb));
  }
  // 流结束但仍有残余缓冲（服务端未以空行收尾）时补一次解析
  buffer += decoder.decode();
  if (buffer.trim()) {
    parseSse(buffer + '\n\n').events.forEach((ev) => dispatch(ev, cb));
  }
}

function dispatch(ev: SseEvent, cb: StreamCallbacks): void {
  let payload: unknown;
  try {
    payload = JSON.parse(ev.data);
  } catch {
    return; // 非 JSON 数据忽略，不打断流
  }
  const p = payload as Record<string, unknown>;
  switch (ev.event) {
    case 'message':
      if (typeof p.delta === 'string') cb.onDelta(p.delta);
      break;
    case 'action':
      if (p.type === 'QUESTION' || p.type === 'FOLLOW_UP' || p.type === 'NEXT') {
        cb.onAction(p.type);
      }
      break;
    case 'metrics':
      cb.onMetrics({
        inputTokens: Number(p.inputTokens ?? 0),
        outputTokens: Number(p.outputTokens ?? 0),
        latencyMs: Number(p.latencyMs ?? 0),
      });
      break;
    case 'done':
      cb.onDone(Number(p.round ?? 0));
      break;
    case 'error':
      cb.onErrorEvent(typeof p.message === 'string' ? p.message : '生成失败');
      break;
  }
}
