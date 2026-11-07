import { useCallback, useEffect, useRef, useState } from 'react';
import { fetchAsrHealth, transcribeAudio } from '../api/asr';
import {
  getSpeechRecognitionConstructor,
  recorderMimeType,
  speechErrorMessage,
  speechSupported,
  type SpeechRecognitionLike,
} from '../lib/speechInput';

interface UseSpeechInputOptions {
  onTranscript: (transcript: string) => void;
}

/**
 * 语音输入双通道：
 * 服务端 ASR 可用时（/api/asr/health 200）走 MediaRecorder 录音→后端 paraformer 识别，
 * 免代理稳定可用；不可用回落浏览器 SpeechRecognition（识别请求发往国外服务，需代理）。
 */
export function useSpeechInput({ onTranscript }: UseSpeechInputOptions) {
  const [browserSupported] = useState(() => speechSupported(window));
  const [serverAsr, setServerAsr] = useState(false);
  const [listening, setListening] = useState(false);
  const [transcribing, setTranscribing] = useState(false);
  const [error, setError] = useState('');
  const recognitionRef = useRef<SpeechRecognitionLike | null>(null);
  const recorderRef = useRef<MediaRecorder | null>(null);
  const chunksRef = useRef<Blob[]>([]);
  const transcriptRef = useRef(onTranscript);
  const mountedRef = useRef(true);

  const supported = browserSupported || serverAsr;

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
    };
  }, []);

  // 服务端 ASR 探测：health 200 即优先走录音上传通道
  useEffect(() => {
    fetchAsrHealth()
      .then((ok) => setServerAsr(ok))
      .catch(() => {});
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
      setListening(true);
    } catch {
      if (mountedRef.current) setError('无法访问麦克风，请检查浏览器权限后重试');
    }
  }, []);

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
      setListening(true);
    } catch {
      recognition.abort();
      recognitionRef.current = null;
      setError('语音识别启动失败，请重试');
    }
  }, []);

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
      recorder.stop();
      return;
    }
    recognitionRef.current?.stop();
  }, []);

  return { supported, listening, transcribing, error, start, stop };
}
