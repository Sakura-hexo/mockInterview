import { useCallback, useEffect, useRef, useState } from 'react';
import { fetchAsrHealth, transcribeAudio } from '../api/asr';
import { createVoiceGate, rmsLevel } from '../lib/voiceActivity';
import {
  getSpeechRecognitionConstructor,
  MAX_RECORD_MS,
  recorderMimeType,
  speechErrorMessage,
  speechSupported,
  type SpeechRecognitionLike,
} from '../lib/speechInput';

interface UseSpeechInputOptions {
  onTranscript: (transcript: string) => void;
  /**
   * 语音面试模式：录音中检测到「先开口、后停顿」自动停止并上传识别，
   * 用户不必再点一次按钮。仅服务端录音通道有效（浏览器识别通道拿不到原始音频）。
   */
  autoStopOnSilence?: boolean;
}

/**
 * 语音输入双通道：
 * 服务端 ASR 可用时（/api/asr/health 200）走 MediaRecorder 录音→后端 paraformer 识别，
 * 免代理稳定可用；不可用回落浏览器 SpeechRecognition（识别请求发往国外服务，需代理）。
 */
export function useSpeechInput({ onTranscript, autoStopOnSilence = false }: UseSpeechInputOptions) {
  const [browserSupported] = useState(() => speechSupported(window));
  const [serverAsr, setServerAsr] = useState(false);
  const [listening, setListening] = useState(false);
  const [transcribing, setTranscribing] = useState(false);
  const [error, setError] = useState('');
  const [elapsedMs, setElapsedMs] = useState(0);
  const [capped, setCapped] = useState(false);
  /** 当前帧音量（RMS 原始值），语音面试模式的电平柱与自动断句都读它 */
  const [level, setLevel] = useState(0);
  const recognitionRef = useRef<SpeechRecognitionLike | null>(null);
  const recorderRef = useRef<MediaRecorder | null>(null);
  const chunksRef = useRef<Blob[]>([]);
  const transcriptRef = useRef(onTranscript);
  const mountedRef = useRef(true);
  const startedAtRef = useRef(0);
  const audioCtxRef = useRef<AudioContext | null>(null);
  const analyserRef = useRef<AnalyserNode | null>(null);
  const levelTimerRef = useRef<number | null>(null);
  const gateRef = useRef(createVoiceGate());
  const autoStopRef = useRef(autoStopOnSilence);
  // 断句回调要在采样循环里调用 stop，而 stop 又依赖清理函数，用 ref 断开首尾依赖
  const stopRef = useRef<() => void>(() => {});

  const supported = browserSupported || serverAsr;

  /** 每段开始时重置计时与封段标记。 */
  const markSegmentStart = useCallback(() => {
    startedAtRef.current = Date.now();
    setElapsedMs(0);
    setCapped(false);
    gateRef.current.reset();
  }, []);

  useEffect(() => {
    autoStopRef.current = autoStopOnSilence;
  }, [autoStopOnSilence]);

  /** 停掉电平监测并复位音量：录音结束、组件卸载、启动失败三条路径都要走。 */
  const cleanupLevelMonitor = useCallback(() => {
    if (levelTimerRef.current !== null) {
      window.clearInterval(levelTimerRef.current);
      levelTimerRef.current = null;
    }
    analyserRef.current = null;
    const ctx = audioCtxRef.current;
    audioCtxRef.current = null;
    if (ctx && ctx.state !== 'closed') {
      void ctx.close().catch(() => {});
    }
    setLevel(0);
  }, []);

  useEffect(() => {
    transcriptRef.current = onTranscript;
  }, [onTranscript]);

  useEffect(() => {
    mountedRef.current = true;
    return () => {
      mountedRef.current = false;
      recognitionRef.current?.abort();
      recognitionRef.current = null;
      const recorder = recorderRef.current;
      recorderRef.current = null;
      // 停录触发 onstop 释放音轨；卸载后不再上传识别
      if (recorder && recorder.state === 'recording') recorder.stop();
      if (levelTimerRef.current !== null) {
        window.clearInterval(levelTimerRef.current);
        levelTimerRef.current = null;
      }
      void audioCtxRef.current?.close().catch(() => {});
      audioCtxRef.current = null;
    };
  }, []);

  // 服务端 ASR 探测：health 200 即优先走录音上传通道
  useEffect(() => {
    fetchAsrHealth()
      .then((ok) => setServerAsr(ok))
      .catch(() => {});
  }, []);

  /**
   * 录音期间挂载音量监测：复用同一路 MediaStream（不额外开麦），
   * 每 100ms 取一帧时域波形算 RMS，驱动电平柱与自动断句。
   * AudioContext 不可用时静默跳过——录音本身不受影响，只是没有自动断句。
   */
  const startLevelMonitor = useCallback((stream: MediaStream) => {
    const AudioCtxCtor =
      window.AudioContext ??
      (window as unknown as { webkitAudioContext?: typeof AudioContext }).webkitAudioContext;
    if (!AudioCtxCtor) return;
    try {
      const ctx = new AudioCtxCtor();
      const analyser = ctx.createAnalyser();
      analyser.fftSize = 1024;
      ctx.createMediaStreamSource(stream).connect(analyser);
      audioCtxRef.current = ctx;
      analyserRef.current = analyser;
      const buffer = new Float32Array(analyser.fftSize);
      levelTimerRef.current = window.setInterval(() => {
        const node = analyserRef.current;
        if (!node) return;
        node.getFloatTimeDomainData(buffer);
        const value = rmsLevel(buffer);
        setLevel(value);
        if (!autoStopRef.current) return;
        if (gateRef.current.push(value, performance.now())) {
          // 说完了：与手动停走同一条收尾路径，识别结果照样回到 onTranscript
          autoStopRef.current = false;
          stopRef.current();
        }
      }, 100);
    } catch {
      // 取不到音频上下文时退化为手动停：不影响主流程
    }
  }, []);

  const startRecording = useCallback(async () => {
    try {
      const stream = await navigator.mediaDevices.getUserMedia({ audio: true });
      const mimeType = recorderMimeType(window);
      const recorder = mimeType
        ? new MediaRecorder(stream, { mimeType })
        : new MediaRecorder(stream);
      chunksRef.current = [];
      recorder.ondataavailable = (event) => {
        if (event.data.size > 0) chunksRef.current.push(event.data);
      };
      recorder.onstop = () => {
        stream.getTracks().forEach((track) => track.stop());
        cleanupLevelMonitor();
        const blob = new Blob(chunksRef.current, { type: recorder.mimeType || 'audio/webm' });
        chunksRef.current = [];
        if (!mountedRef.current) return;
        if (blob.size === 0) {
          setTranscribing(false);
          return;
        }
        setTranscribing(true);
        transcribeAudio(blob)
          .then((text) => {
            if (text) transcriptRef.current(text);
          })
          .catch(() => {
            if (mountedRef.current) setError('语音识别失败，请重试或手动输入');
          })
          .finally(() => {
            if (mountedRef.current) setTranscribing(false);
          });
      };
      recorder.start();
      recorderRef.current = recorder;
      setError('');
      markSegmentStart();
      startLevelMonitor(stream);
      setListening(true);
    } catch {
      cleanupLevelMonitor();
      if (mountedRef.current) setError('无法访问麦克风，请检查浏览器权限后重试');
    }
  }, [markSegmentStart, startLevelMonitor, cleanupLevelMonitor]);

  const startBrowserRecognition = useCallback(() => {
    const Recognition = getSpeechRecognitionConstructor(window);
    if (!Recognition) {
      setError('当前浏览器不支持语音输入，请改用 Chrome 或 Edge');
      return;
    }

    const recognition = new Recognition();
    recognition.lang = 'zh-Hans-CN';
    recognition.continuous = true;
    recognition.interimResults = false;
    recognition.onresult = (event) => {
      for (let index = event.resultIndex; index < event.results.length; index += 1) {
        const result = event.results[index];
        const transcript = result[0]?.transcript;
        if (result.isFinal && transcript) transcriptRef.current(transcript);
      }
    };
    recognition.onerror = (event) => {
      if (mountedRef.current) setError(speechErrorMessage(event.error));
    };
    recognition.onend = () => {
      if (mountedRef.current) setListening(false);
    };

    try {
      recognition.start();
      recognitionRef.current = recognition;
      setError('');
      markSegmentStart();
      setListening(true);
    } catch {
      recognition.abort();
      recognitionRef.current = null;
      setError('语音识别启动失败，请重试');
    }
  }, [markSegmentStart]);

  const start = useCallback(() => {
    if (listening || transcribing) return;
    if (serverAsr) {
      void startRecording();
      return;
    }
    startBrowserRecognition();
  }, [listening, transcribing, serverAsr, startRecording, startBrowserRecognition]);

  const stop = useCallback(() => {
    const recorder = recorderRef.current;
    if (recorder) {
      recorderRef.current = null;
      setListening(false);
      cleanupLevelMonitor();
      recorder.stop();
      return;
    }
    recognitionRef.current?.stop();
  }, [cleanupLevelMonitor]);

  useEffect(() => {
    stopRef.current = stop;
  }, [stop]);

  // 单段计时：每 250ms 刷新显示；到 MAX_RECORD_MS 自动停，走与手动停同一条上传识别路径。
  // 计时基于起始时刻差值而非累加，后台标签页定时器降频只会让显示变粗、不会越走越慢。
  useEffect(() => {
    if (!listening) return;
    const timer = setInterval(() => {
      const elapsed = Date.now() - startedAtRef.current;
      setElapsedMs(elapsed);
      if (elapsed >= MAX_RECORD_MS) {
        setCapped(true);
        stop();
      }
    }, 250);
    return () => clearInterval(timer);
  }, [listening, stop]);

  return { supported, listening, transcribing, error, elapsedMs, capped, level, start, stop };
}
