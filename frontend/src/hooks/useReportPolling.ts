import { useEffect, useRef, useState } from 'react';
import { getReport } from '../api/interview';
import { deriveStage, nextPollDelay } from '../lib/reportProgress';
import type { InterviewReport } from '../types';

// 报告页轮询 hook（前端架构方案第 6 节）：
// 202 生成中 → 3 秒轮询、5 次后退避 10 秒封顶；分步进度由轮询次数推导

export function useReportPolling(sessionId: string) {
  const [report, setReport] = useState<InterviewReport | null>(null);
  const [generating, setGenerating] = useState(false);
  const [stage, setStage] = useState(0);
  const [loadError, setLoadError] = useState('');
  const [loaded, setLoaded] = useState(false);
  const timerRef = useRef<ReturnType<typeof setTimeout> | null>(null);
  const aliveRef = useRef(true);

  useEffect(() => {
    aliveRef.current = true;
    return () => {
      aliveRef.current = false;
      if (timerRef.current) clearTimeout(timerRef.current);
    };
  }, []);

  useEffect(() => {
    let generatingPolls = 0;

    const tick = async () => {
      try {
        const r = await getReport(sessionId);
        if (r.state === 'DONE') {
          if (aliveRef.current) {
            setReport(r.report);
            setGenerating(false);
            setLoaded(true);
          }
          return;
        }
        generatingPolls += 1;
        if (aliveRef.current) {
          setGenerating(true);
          setStage(deriveStage(generatingPolls));
          setLoaded(true);
        }
      } catch {
        // 404 等真实错误停止轮询；401 由 http.ts 统一跳登录
        if (aliveRef.current) {
          setLoadError('报告加载失败，请刷新重试');
          setLoaded(true);
        }
        return;
      }
      // 按节奏调度下一轮（3 秒起步，5 次后退避 10 秒封顶）
      if (aliveRef.current) {
        timerRef.current = setTimeout(tick, nextPollDelay(generatingPolls));
      }
    };

    tick();
    return () => {
      if (timerRef.current) clearTimeout(timerRef.current);
    };
  }, [sessionId]);

  return { report, generating, stage, loadError, loaded };
}
