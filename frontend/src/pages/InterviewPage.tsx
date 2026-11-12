import { KeyboardEvent, useCallback, useEffect, useRef, useState } from 'react';
import { useNavigate, useParams } from 'react-router-dom';
import { endInterview } from '../api/interview';
import { ApiError } from '../api/http';
import { useInterviewStream } from '../hooks/useInterviewStream';
import { useSpeechInput } from '../hooks/useSpeechInput';
import { useSpeechOutput } from '../hooks/useSpeechOutput';
import MessageBubble from '../components/MessageBubble';
import ActivityStream from '../components/ActivityStream';
import {
  appendTranscript,
  COUNTDOWN_WINDOW_MS,
  formatRecordElapsed,
  MAX_RECORD_MS,
  toSimplifiedChinese,
} from '../lib/speechInput';
import { describeVoiceState, litBars } from '../lib/voiceActivity';
import type { AnnotatedMessage } from '../lib/interviewPhase';
import './InterviewPage.css';

const DIRECTION_LABEL: Record<string, string> = { JAVA_BACKEND: 'Java 后端' };
const DIFFICULTY_LABEL: Record<string, string> = { EASY: '简单', MEDIUM: '中等', HARD: '困难' };
// 服务端神经嗓音的中文名（与后端边车清单对应）
const SERVER_VOICE_LABEL: Record<string, string> = {
  'zh-CN-XiaoxiaoNeural': '晓晓 · 女声',
  'zh-CN-YunxiNeural': '云希 · 男声',
  'zh-CN-YunyangNeural': '云扬 · 男声',
};

// 自动读题开关持久化：默认开（TTS 的主场景就是面试官开口问），关掉记住选择
const AUTO_READ_KEY = 'interview-auto-read';
// 语音面试模式持久化：开着的用户下次进来还是语音，不用每场重新开
const VOICE_MODE_KEY = 'interview-voice-mode';
// 面试官读完到自动开麦之间的留白：留出「轮到你了」的心理切换，也避开扬声器尾音被录进去
const AUTO_LISTEN_DELAY_MS = 600;
const VOICE_BARS = 18;

/** 语音面板的麦克风图标（避免 emoji 在不同字体下渲染不一致） */
function MicIcon() {
  return (
    <svg viewBox="0 0 24 24" width="26" height="26" aria-hidden="true" focusable="false">
      <path
        d="M12 15a3.5 3.5 0 0 0 3.5-3.5v-5a3.5 3.5 0 1 0-7 0v5A3.5 3.5 0 0 0 12 15Z"
        fill="currentColor"
      />
      <path
        d="M5.75 11.5a.9.9 0 0 1 1.8 0 4.45 4.45 0 0 0 8.9 0 .9.9 0 0 1 1.8 0 6.25 6.25 0 0 1-5.35 6.18v2.07a.9.9 0 0 1-1.8 0v-2.07A6.25 6.25 0 0 1 5.75 11.5Z"
        fill="currentColor"
      />
    </svg>
  );
}

// 面试聊天页 /interview/:id（页面方案 4.2，视觉按 V1 定稿设计稿 04/06）
export default function InterviewPage() {
  const { id = '' } = useParams();
  const navigate = useNavigate();
  const {
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
  } = useInterviewStream(id);

  const [draft, setDraft] = useState('');
  const [ending, setEnding] = useState(false);
  const [endError, setEndError] = useState('');
  const listRef = useRef<HTMLDivElement>(null);

  const readOnly = !!session && (session.status === 'ENDED' || session.status === 'REPORTED');

  // 语音面试模式：面试官读完自动开麦，用户说完自动提交，不再需要鼠标
  const [voiceMode, setVoiceMode] = useState(() => localStorage.getItem(VOICE_MODE_KEY) === '1');
  const [voiceDraft, setVoiceDraft] = useState('');
  const [voiceHint, setVoiceHint] = useState('');

  // 自动流程（播报结束、识别结束、切换模式）都在异步回调里跑，
  // 一律读 ref 而不是渲染闭包，避免拿到上一帧的 streaming / readOnly
  const voiceModeRef = useRef(voiceMode);
  const readOnlyRef = useRef(readOnly);
  const streamingRef = useRef(streaming);
  const annotatedRef = useRef<AnnotatedMessage[]>(annotated);
  const sendRef = useRef(send);
  useEffect(() => {
    voiceModeRef.current = voiceMode;
    readOnlyRef.current = readOnly;
    streamingRef.current = streaming;
    annotatedRef.current = annotated;
    sendRef.current = send;
  });

  const {
    supported,
    listening,
    transcribing,
    error: speechError,
    elapsedMs,
    capped,
    level,
    start: startSpeech,
    stop: stopSpeech,
  } = useSpeechInput({
    autoStopOnSilence: voiceMode,
    onTranscript: (transcript) => {
      const text = toSimplifiedChinese(transcript).trim();
      if (voiceModeRef.current) {
        setVoiceDraft(text);
        if (!text) {
          setVoiceHint('没有听清，请靠近麦克风再说一次');
          return;
        }
        if (readOnlyRef.current) return;
        setVoiceHint('');
        // 语音模式不需要再点发送：识别完成即视为答完这一句
        sendRef.current(text);
        return;
      }
      setDraft((current) => appendTranscript(current, transcript));
    },
  });

  const supportedRef = useRef(supported);
  const startSpeechRef = useRef(startSpeech);
  const stopSpeechRef = useRef(stopSpeech);
  const listeningRef = useRef(listening);
  useEffect(() => {
    supportedRef.current = supported;
    startSpeechRef.current = startSpeech;
    stopSpeechRef.current = stopSpeech;
    listeningRef.current = listening;
  });

  // 单段录音计时：剩余不足 15 秒切红字倒计时，到点自动停（钩子内执法）
  const recordRemainingMs = MAX_RECORD_MS - elapsedMs;
  const countdownActive = listening && recordRemainingMs <= COUNTDOWN_WINDOW_MS;

  const autoListenTimer = useRef<number | null>(null);
  const clearAutoListen = useCallback(() => {
    if (autoListenTimer.current !== null) {
      window.clearTimeout(autoListenTimer.current);
      autoListenTimer.current = null;
    }
  }, []);

  /** 延时自动开麦：等到麦克风真正可用再判定一次前置条件，条件不满足就静默放弃。 */
  const scheduleAutoListen = useCallback(
    (delay = AUTO_LISTEN_DELAY_MS) => {
      clearAutoListen();
      autoListenTimer.current = window.setTimeout(() => {
        autoListenTimer.current = null;
        if (!voiceModeRef.current || readOnlyRef.current || streamingRef.current) return;
        if (!supportedRef.current || listeningRef.current) return;
        setVoiceDraft('');
        startSpeechRef.current();
      }, delay);
    },
    [clearAutoListen],
  );

  // 面试官读题：气泡按钮 + 顶栏自动读题开关（ENDED 只读回看不自动读）
  const [speakingId, setSpeakingId] = useState<string | null>(null);
  const [autoRead, setAutoRead] = useState(() => localStorage.getItem(AUTO_READ_KEY) !== '0');
  const [ttsError, setTtsError] = useState('');
  const tts = useSpeechOutput({
    onDone: (reason) => {
      setSpeakingId(null);
      // 只有「自然读完」才接麦；用户主动关掉朗读时不抢麦
      if (reason === 'ended' && voiceModeRef.current && !readOnlyRef.current) {
        scheduleAutoListen();
      }
    },
  });

  const toggleSpeak = useCallback(
    (message: AnnotatedMessage) => {
      setTtsError('');
      const messageId = message.id ?? message.content;
      if (speakingId === messageId) {
        tts.stop();
        setSpeakingId(null);
        return;
      }
      setSpeakingId(messageId);
      tts.speak(message.content);
    },
    [speakingId, tts],
  );

  const lastInterviewerRef = useRef<string | null>(null);

  // 新面试官消息完成流式后自动朗读：
  // 文本模式挂载时只登记基线不补读历史；语音模式要接着上次的口型，进页面就读最后一题
  useEffect(() => {
    if (!autoRead && !voiceMode) return;
    if (!tts.supported || readOnly) return;
    const last = [...annotated].reverse().find((m) => m.role === 'INTERVIEWER');
    if (!last || last.streaming) return;
    const key = last.id ?? last.content;
    if (lastInterviewerRef.current === null) {
      lastInterviewerRef.current = key;
      if (!voiceMode) return;
    }
    if (lastInterviewerRef.current === key) return;
    lastInterviewerRef.current = key;
    // 换题时先停下正在录的回答，避免面试官的声音被录进去
    if (listeningRef.current) stopSpeechRef.current();
    clearAutoListen();
    setTtsError('');
    setSpeakingId(key);
    tts.speak(last.content);
  }, [annotated, autoRead, voiceMode, tts.supported, readOnly, tts.speak, clearAutoListen]);

  // 新消息与流式增量时自动滚到底部
  useEffect(() => {
    const el = listRef.current;
    if (el) el.scrollTop = el.scrollHeight;
  }, [annotated]);

  // 卸载时清掉待触发的自动开麦，避免离开页面后麦克风被拉起
  useEffect(() => clearAutoListen, [clearAutoListen]);

  const toggleVoiceMode = useCallback(() => {
    const next = !voiceModeRef.current;
    voiceModeRef.current = next;
    setVoiceMode(next);
    localStorage.setItem(VOICE_MODE_KEY, next ? '1' : '0');
    setVoiceHint('');
    if (!next) {
      clearAutoListen();
      tts.stop();
      setSpeakingId(null);
      stopSpeechRef.current();
      return;
    }
    // 开模式即读最后一题，读完自动开麦；没有历史题时直接进入聆听
    const last = [...annotatedRef.current].reverse().find((m) => m.role === 'INTERVIEWER' && !m.streaming);
    if (last && !readOnlyRef.current && tts.supported) {
      const key = last.id ?? last.content;
      lastInterviewerRef.current = key;
      setTtsError('');
      setSpeakingId(key);
      tts.speak(last.content);
      return;
    }
    scheduleAutoListen(200);
  }, [clearAutoListen, scheduleAutoListen, tts]);

  function handleKeyDown(e: KeyboardEvent<HTMLTextAreaElement>) {
    if (e.key === 'Enter' && !e.shiftKey) {
      e.preventDefault(); // Enter 发送、Shift+Enter 换行（页面方案 4.2）
      if (!streaming && draft.trim()) {
        send(draft);
        setDraft('');
      }
    }
  }

  async function handleEnd() {
    // 结束面试二次确认（页面方案待确认事项 5，默认保留 confirm）
    if (!window.confirm('确定结束本场面试吗？结束后将生成复盘报告。')) return;
    setEnding(true);
    setEndError('');
    clearAutoListen();
    tts.stop();
    stopSpeech();
    try {
      await endInterview(id);
      navigate(`/report/${id}`);
    } catch (err) {
      setEnding(false);
      setEndError(err instanceof ApiError ? `结束失败：${err.message}` : '结束失败，请重试');
    }
  }

  if (!loaded) {
    return <div className="home-placeholder">会话加载中…</div>;
  }

  const statusBadge = readOnly ? (
    <span className={`badge ${session!.status === 'REPORTED' ? 'badge-green' : 'badge-yellow'}`}>
      {session!.status === 'REPORTED' ? '已出报告' : '已结束'}
    </span>
  ) : (
    <span className="badge badge-blue mono">IN_PROGRESS</span>
  );

  const title = [
    session ? `${readOnly ? '面试记录' : '面试中'} · ${DIRECTION_LABEL[session.direction] ?? session.direction}` : '面试中',
    session ? `${DIFFICULTY_LABEL[session.difficulty] ?? session.difficulty}难度` : '',
    phase.progressText,
  ]
    .filter(Boolean)
    .join(' · ');

  const voiceState = describeVoiceState({
    streaming,
    recovering,
    speaking: tts.speaking,
    listening,
    transcribing,
    supported,
  });
  const voiceAvailable = supported || tts.supported;

  return (
    <>
      <header className="chat-header">
        <div className="title">
          {title}
          {statusBadge}
        </div>
        <div className="actions">
          {voiceAvailable && (
            <button
              type="button"
              className={`btn btn-ghost voice-mode-btn ${voiceMode ? 'on' : ''}`}
              onClick={toggleVoiceMode}
              title="语音面试：面试官读完题自动开麦，你说完停顿即自动提交，全程不用碰键盘"
            >
              语音面试{voiceMode ? '开' : '关'}
            </button>
          )}
          {tts.supported && !voiceMode && (
            <>
              <button
                type="button"
                className={`btn btn-ghost auto-read-btn ${autoRead ? 'on' : ''}`}
                onClick={() => {
                  const next = !autoRead;
                  localStorage.setItem(AUTO_READ_KEY, next ? '1' : '0');
                  setAutoRead(next);
                  if (next) {
                    // 开关打开的瞬间立刻朗读最新一题：此前完成的题不会补读，
                    // 立刻出声让用户当场确认开关生效
                    const last = [...annotated]
                      .reverse()
                      .find((m) => m.role === 'INTERVIEWER' && !m.streaming);
                    if (last && !readOnly && tts.supported) {
                      const key = last.id ?? last.content;
                      lastInterviewerRef.current = key;
                      setTtsError('');
                      setSpeakingId(key);
                      tts.speak(last.content);
                    }
                  } else {
                    tts.stop();
                    setSpeakingId(null);
                  }
                }}
                title={autoRead ? '新题完成生成后自动朗读，点击关闭' : '开启后新题自动朗读，并立刻朗读最新一题'}
              >
                自动读题{autoRead ? '开' : '关'}
              </button>
            </>
          )}
          {tts.supported && (
            <>
              {tts.serverVoices.length > 0 ? (
                <select
                  className="voice-select"
                  value={tts.serverVoices.includes(tts.voiceName) ? tts.voiceName : ''}
                  onChange={(e) => tts.setVoice(e.target.value)}
                  title="读题嗓音：服务端神经合成（微软晓晓同款），暂不可用时回落浏览器朗读"
                >
                  <option value="">嗓音·自动</option>
                  {tts.serverVoices.map((voice) => (
                    <option key={voice} value={voice}>
                      {SERVER_VOICE_LABEL[voice] ?? voice}
                    </option>
                  ))}
                </select>
              ) : tts.voices.length > 1 ? (
                <select
                  className="voice-select"
                  value={tts.voiceName}
                  onChange={(e) => tts.setVoice(e.target.value)}
                  title="读题嗓音：自动按自然嗓音优先挑选，或手动指定"
                >
                  <option value="">嗓音·自动</option>
                  {tts.voices.map((voice) => (
                    <option key={voice.name} value={voice.name}>
                      {voice.name.split(' - ')[0]}
                    </option>
                  ))}
                </select>
              ) : null}
            </>
          )}
          {!readOnly && (
            <button className="btn btn-danger" onClick={handleEnd} disabled={ending}>
              {ending ? '结束中…' : '结束面试'}
            </button>
          )}
        </div>
      </header>

      {ttsError && <div className="streaming-hint tts-error">{ttsError}</div>}

      {!readOnly && (streaming || recovering) && (
        <div className="streaming-hint">
          <span className="pulse" />
          <span>
            {recovering
              ? '面试官回复生成中，自动同步中（每 3 秒）…'
              : `面试官${
                  (annotated.find((m) => m.streaming)?.content.length ?? 0) > 0
                    ? `正在回复…已生成 ${annotated.find((m) => m.streaming)!.content.length} 字`
                    : '思考与检索中…'
                } · ${Math.max(0, Math.floor((streamNow - streamStart) / 1000))}s`}
          </span>
        </div>
      )}

      <div className="main-body" ref={listRef}>
        <div className="message-list">
          {loadError && <div className="interview-error">{loadError}</div>}
          {endError && <div className="interview-error">{endError}</div>}
          {annotated.map((m) => (
            <MessageBubble
              key={m.id ?? m.content}
              message={m}
              onToggleSpeak={m.role === 'INTERVIEWER' ? () => toggleSpeak(m) : undefined}
              speaking={!!m.id && m.id === speakingId}
              ttsSupported={tts.supported}
            />
          ))}
          <ActivityStream items={activities} />
        </div>
      </div>

      {!readOnly && voiceMode && (
        <div className={`voice-panel tone-${voiceState.tone}`}>
          <div className="voice-head">
            <span className="voice-dot" />
            <span className="voice-status">{voiceState.text}</span>
            {listening && (
              <span className={`voice-timer mono ${countdownActive ? 'hot' : ''}`}>
                {countdownActive
                  ? `剩余 ${Math.max(1, Math.ceil(recordRemainingMs / 1000))} 秒`
                  : formatRecordElapsed(elapsedMs)}
              </span>
            )}
            <button type="button" className="voice-switch" onClick={toggleVoiceMode}>
              切回键盘输入
            </button>
          </div>

          <div className="voice-body">
            <button
              type="button"
              className={`voice-mic ${listening ? 'on' : ''}`}
              onClick={() => {
                setVoiceHint('');
                if (listening) {
                  stopSpeech();
                  return;
                }
                setVoiceDraft('');
                startSpeech();
              }}
              disabled={transcribing || streaming || !supported}
              title={listening ? '结束这段回答并提交' : '开始回答'}
              aria-label={listening ? '结束这段回答并提交' : '开始回答'}
            >
              <MicIcon />
            </button>

            <div className="voice-meter" aria-hidden="true">
              {Array.from({ length: VOICE_BARS }, (_, index) => (
                <span
                  key={index}
                  className={`voice-bar ${listening && index < litBars(level, VOICE_BARS) ? 'on' : ''}`}
                />
              ))}
            </div>

            <div className="voice-transcript">
              {voiceDraft ? (
                <p className="voice-said">{voiceDraft}</p>
              ) : (
                <p className="voice-placeholder">
                  {listening ? '正在听你说…' : transcribing ? '识别中…' : '你的回答会实时转成文字显示在这里'}
                </p>
              )}
            </div>
          </div>

          <div className="voice-foot">
            {speechError && <span className="voice-error">{speechError}</span>}
            {!supported && (
              <span className="voice-error">
                服务端语音识别未开启，可在后端 .env 设置 ASR_PROVIDER=sherpa 后重启；当前请切回键盘输入
              </span>
            )}
            {capped && !listening && !transcribing && (
              <span className="voice-tip">单段已达上限，点麦克风可以接着说</span>
            )}
            {voiceHint && <span className="voice-tip">{voiceHint}</span>}
            {!speechError && !voiceHint && supported && (
              <span className="voice-tip">
                说完停顿约 1.5 秒自动提交；想自己控节奏就再点一次麦克风结束这段
              </span>
            )}
          </div>
        </div>
      )}

      {!readOnly && !voiceMode && (
        <div className="input-area">
          <textarea
            placeholder={
              streaming ? '面试官正在回复，请稍候…' : '输入你的回答…（Enter 发送，Shift+Enter 换行）'
            }
            rows={2}
            value={draft}
            onChange={(e) => setDraft(e.target.value)}
            onKeyDown={handleKeyDown}
            disabled={streaming}
          />
          <button
            className={`speech-btn ${listening ? 'speech-listening' : ''} ${countdownActive ? 'speech-countdown' : ''}`}
            onClick={listening ? stopSpeech : startSpeech}
            disabled={streaming || !supported || transcribing}
            title={supported ? '语音输入，识别后可再编辑' : '当前浏览器不支持语音输入'}
          >
            {listening
              ? countdownActive
                ? `剩余 ${Math.max(1, Math.ceil(recordRemainingMs / 1000))} 秒`
                : `录制中 ${formatRecordElapsed(elapsedMs)}`
              : transcribing
                ? '识别中'
                : '语音'}
          </button>
          {streaming ? (
            <button className="stop-btn" onClick={stop}>
              停止
            </button>
          ) : (
            <button
              className="send-btn"
              onClick={() => {
                if (draft.trim()) {
                  send(draft);
                  setDraft('');
                }
              }}
              disabled={!draft.trim()}
            >
              发送
            </button>
          )}
          {speechError && <div className="speech-error">{speechError}</div>}
          {capped && !listening && !transcribing && (
            <div className="speech-hint">单段已达上限，点击「语音」继续说，识别结果会接在草稿后面</div>
          )}
          {!supported && (
            <div className="speech-error">当前浏览器不支持语音输入，请改用 Chrome 或 Edge，或手动输入</div>
          )}
        </div>
      )}
    </>
  );
}
