import { useCallback, useEffect, useRef, useState } from 'react';
import {
  getSpeechRecognitionConstructor,
  speechErrorMessage,
  speechSupported,
  type SpeechRecognitionLike,
} from '../lib/speechInput';

interface UseSpeechInputOptions {
  onTranscript: (transcript: string) => void;
}

export function useSpeechInput({ onTranscript }: UseSpeechInputOptions) {
  const [supported] = useState(() => speechSupported(window));
  const [listening, setListening] = useState(false);
  const [error, setError] = useState('');
  const recognitionRef = useRef<SpeechRecognitionLike | null>(null);
  const transcriptRef = useRef(onTranscript);
  const mountedRef = useRef(true);

  useEffect(() => {
    transcriptRef.current = onTranscript;
  }, [onTranscript]);

  useEffect(() => {
    mountedRef.current = true;
    return () => {
      mountedRef.current = false;
      recognitionRef.current?.abort();
      recognitionRef.current = null;
    };
  }, []);

  const start = useCallback(() => {
    if (listening) return;
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
  }, [listening]);

  const stop = useCallback(() => {
    recognitionRef.current?.stop();
  }, []);

  return { supported, listening, error, start, stop };
}
