import { useCallback, useEffect, useRef, useState } from 'react';
import { fetchTtsAudio, fetchTtsVoices } from '../api/tts';
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

/** 朗读结束的两种成因：自然读完（ended）与被打断/主动停止（stopped）。 */
export type SpeechEndReason = 'ended' | 'stopped';

interface UseSpeechOutputOptions {
  /**
   * 朗读结束（完成、出错或被停止）时回调，驱动页面层清当前朗读消息标记。
   * 语音面试模式据 reason 区分「面试官问完了，该轮到候选人」与「用户主动打断」。
   */
  onDone?: (reason: SpeechEndReason) => void;
}

const VOICE_NAME_KEY = 'TTS_VOICE_NAME';

/**
 * 面试官读题朗读，双通道：
 * 服务端 TTS 可用时（/api/tts/voices 非空）走后端 mp3 神经嗓音，任何浏览器听感一致；
 * 不可用或合成失败回落浏览器 speechSynthesis（逐句入队规避 Chrome 长段截断）。
 */
export function useSpeechOutput({ onDone }: UseSpeechOutputOptions = {}) {
  const [browserSupported] = useState(() => speechOutputSupported(window));
  const [speaking, setSpeaking] = useState(false);
  const [error, setError] = useState('');
  const [voices, setVoices] = useState<SpeechSynthesisVoice[]>([]);
  const [serverVoices, setServerVoices] = useState<string[]>([]);
  const [voiceName, setVoiceName] = useState(() => localStorage.getItem(VOICE_NAME_KEY) ?? '');
  const audioRef = useRef<HTMLAudioElement | null>(null);
  const doneRef = useRef(onDone);
  const mountedRef = useRef(true);

  const supported = browserSupported || serverVoices.length > 0;

  useEffect(() => {
    doneRef.current = onDone;
  }, [onDone]);

  useEffect(() => {
    mountedRef.current = true;
    return () => {
      mountedRef.current = false;
      audioRef.current?.pause();
      audioRef.current = null;
      window.speechSynthesis?.cancel();
    };
  }, []);

  // 浏览器嗓音列表异步就绪：挂载即取一次并监听 voiceschanged（Chrome 首次常为空）
  useEffect(() => {
    const synthesis = window.speechSynthesis;
    if (!synthesis) return;
    const refresh = () => setVoices(chineseVoiceOptions(synthesis.getVoices()));
    refresh();
    synthesis.addEventListener('voiceschanged', refresh);
    return () => synthesis.removeEventListener('voiceschanged', refresh);
  }, []);

  // 服务端 TTS 探测：清单非空即优先走服务端合成
  useEffect(() => {
    fetchTtsVoices()
      .then((list) => {
        if (list.length > 0) setServerVoices(list);
      })
      .catch(() => {});
  }, []);

  const setVoice = useCallback((name: string) => {
    setVoiceName(name);
    localStorage.setItem(VOICE_NAME_KEY, name);
  }, []);

  const finish = useCallback((reason: SpeechEndReason) => {
    if (!mountedRef.current) return;
    setSpeaking(false);
    doneRef.current?.(reason);
  }, []);

  const speakViaBrowser = useCallback(
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
        if (remaining === 0) finish('ended');
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

  const speakViaServer = useCallback(
    async (text: string) => {
      const content = stripMarkdown(text);
      if (!content) return;
      const voice = serverVoices.includes(voiceName) ? voiceName : serverVoices[0];
      try {
        const blob = await fetchTtsAudio(content, voice);
        if (!mountedRef.current) return;
        audioRef.current?.pause();
        const audio = new Audio(URL.createObjectURL(blob));
        audioRef.current = audio;
        audio.onended = () => {
          URL.revokeObjectURL(audio.src);
          audioRef.current = null;
          finish('ended');
        };
        audio.onerror = () => {
          URL.revokeObjectURL(audio.src);
          if (mountedRef.current) speakViaBrowser(content);
        };
        setError('');
        await audio.play();
        setSpeaking(true);
      } catch {
        // 服务端合成失败：回落浏览器朗读，不打断用户
        speakViaBrowser(content);
      }
    },
    [serverVoices, voiceName, finish, speakViaBrowser],
  );

  const speak = useCallback(
    (text: string) => {
      if (serverVoices.length > 0) {
        void speakViaServer(text);
        return;
      }
      speakViaBrowser(text);
    },
    [serverVoices, speakViaServer, speakViaBrowser],
  );

  const stop = useCallback(() => {
    if (audioRef.current) {
      audioRef.current.pause();
      audioRef.current = null;
    }
    // cancel 会触发 pending 语句的 onerror(canceled) 走静默收尾，
    // 但 Firefox 等不一定回调，这里主动收尾保证状态复位（重复调用幂等）
    window.speechSynthesis?.cancel();
    finish('stopped');
  }, [finish]);

  return { supported, speaking, error, speak, stop, voices, serverVoices, voiceName, setVoice };
}
