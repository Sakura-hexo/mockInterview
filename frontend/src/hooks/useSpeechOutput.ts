import { useCallback, useEffect, useRef, useState } from 'react';
import {
  chineseVoiceOptions,
  findVoiceByName,
  isSilentCancel,
  pickChineseVoice,
  speechOutputErrorMessage,
  speechOutputSupported,
  splitSentences,
  stripMarkdown,
} from '../lib/speechOutput';

interface UseSpeechOutputOptions {
  /** 朗读结束（完成、出错或被停止）时回调，驱动页面层清当前朗读消息标记。 */
  onDone?: () => void;
}

const VOICE_NAME_KEY = 'TTS_VOICE_NAME';

/**
 * 面试官读题朗读：speak 前先 cancel 替换当前朗读，逐句入队规避 Chrome 长段截断；
 * 用户主动停止触发的 canceled/interrupted 静默不报错；卸载时停掉残留朗读。
 */
export function useSpeechOutput({ onDone }: UseSpeechOutputOptions = {}) {
  const [supported] = useState(() => speechOutputSupported(window));
  const [speaking, setSpeaking] = useState(false);
  const [error, setError] = useState('');
  const [voices, setVoices] = useState<SpeechSynthesisVoice[]>([]);
  const [voiceName, setVoiceName] = useState(() => localStorage.getItem(VOICE_NAME_KEY) ?? '');
  const doneRef = useRef(onDone);
  const mountedRef = useRef(true);

  useEffect(() => {
    doneRef.current = onDone;
  }, [onDone]);

  useEffect(() => {
    mountedRef.current = true;
    return () => {
      mountedRef.current = false;
      window.speechSynthesis?.cancel();
    };
  }, []);

  // 嗓音列表异步就绪：挂载即取一次并监听 voiceschanged（Chrome 首次常为空）
  useEffect(() => {
    const synthesis = window.speechSynthesis;
    if (!synthesis) return;
    const refresh = () => setVoices(chineseVoiceOptions(synthesis.getVoices()));
    refresh();
    synthesis.addEventListener('voiceschanged', refresh);
    return () => synthesis.removeEventListener('voiceschanged', refresh);
  }, []);

  const setVoice = useCallback((name: string) => {
    setVoiceName(name);
    localStorage.setItem(VOICE_NAME_KEY, name);
  }, []);

  const finish = useCallback(() => {
    if (!mountedRef.current) return;
    setSpeaking(false);
    doneRef.current?.();
  }, []);

  const speak = useCallback(
    (text: string) => {
      const synthesis = window.speechSynthesis;
      const Utterance = window.SpeechSynthesisUtterance;
      if (!synthesis || !Utterance) {
        setError('当前浏览器不支持语音播放，请改用 Chrome 或 Edge');
        return;
      }
      synthesis.cancel();
      const parts = splitSentences(stripMarkdown(text));
      if (parts.length === 0) return;
      const voice =
        findVoiceByName(voices, voiceName) ??
        pickChineseVoice(voices.length > 0 ? voices : synthesis.getVoices());
      let remaining = parts.length;
      const settle = () => {
        remaining -= 1;
        if (remaining === 0) finish();
      };
      parts.forEach((part) => {
        const utterance = new Utterance(part);
        utterance.lang = 'zh-CN';
        utterance.rate = 1;
        utterance.voice = voice;
        utterance.onend = settle;
        utterance.onerror = (event) => {
          if (!isSilentCancel(event.error) && mountedRef.current) {
            setError(speechOutputErrorMessage(event));
          }
          settle();
        };
        synthesis.speak(utterance);
      });
      setError('');
      setSpeaking(true);
    },
    [finish, voices, voiceName],
  );

  const stop = useCallback(() => {
    // cancel 会触发 pending 语句的 onerror(canceled) 走静默收尾，
    // 但 Firefox 等不一定回调，这里主动收尾保证状态复位（重复调用幂等）
    window.speechSynthesis?.cancel();
    finish();
  }, [finish]);

  return { supported, speaking, error, speak, stop, voices, voiceName, setVoice };
}
