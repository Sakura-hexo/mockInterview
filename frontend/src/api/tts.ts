// 服务端 TTS 接口：voices 404 = 未启用（前端回落浏览器朗读）；合成走原始 fetch 拿二进制
import { ApiError } from './http';

export async function fetchTtsVoices(): Promise<string[]> {
  const res = await fetch('/api/tts/voices', { credentials: 'include' });
  if (res.status === 404) return [];
  if (!res.ok) throw new ApiError(res.status, 'TTS 嗓音清单拉取失败');
  const body = (await res.json()) as { data?: { voices?: string[] } };
  return body?.data?.voices ?? [];
}

export async function fetchTtsAudio(text: string, voice: string): Promise<Blob> {
  const res = await fetch('/api/tts', {
    method: 'POST',
    credentials: 'include',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ text, voice }),
  });
  if (!res.ok) throw new ApiError(res.status, '语音合成失败');
  return res.blob();
}
