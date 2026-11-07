// 服务端 ASR 接口：health 404 = 未启用（前端回落浏览器识别）；识别上传录音原始字节
import { ApiError } from './http';

export async function fetchAsrHealth(): Promise<boolean> {
  const res = await fetch('/api/asr/health', { credentials: 'include' });
  if (res.status === 404) return false;
  if (!res.ok) throw new ApiError(res.status, 'ASR 探测失败');
  return true;
}

export async function transcribeAudio(blob: Blob): Promise<string> {
  const res = await fetch('/api/asr', {
    method: 'POST',
    credentials: 'include',
    headers: { 'Content-Type': 'application/octet-stream' },
    body: blob,
  });
  if (!res.ok) throw new ApiError(res.status, '语音识别失败');
  const body = (await res.json()) as { data?: { text?: string } };
  return body?.data?.text ?? '';
}
