import { KeyboardEvent, useCallback, useEffect, useRef, useState } from 'react';
import { useNavigate, useParams } from 'react-router-dom';
import { endInterview } from '../api/interview';
import { ApiError } from '../api/http';
import { useInterviewStream } from '../hooks/useInterviewStream';
import { useSpeechInput } from '../hooks/useSpeechInput';
import { useSpeechOutput } from '../hooks/useSpeechOutput';
import MessageBubble from '../components/MessageBubble';
import ActivityStream from '../components/ActivityStream';
import { appendTranscript } from '../lib/speechInput';
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
  const { supported, listening, transcribing, error: speechError, start, stop: stopSpeech } = useSpeechInput({
    onTranscript: (transcript) => setDraft((draft) => appendTranscript(draft, transcript)),
  });

  // 面试官读题：气泡按钮 + 顶栏自动读题开关（ENDED 只读回看不自动读）
  const [speakingId, setSpeakingId] = useState<string | null>(null);
  const [autoRead, setAutoRead] = useState(() => localStorage.getItem(AUTO_READ_KEY) !== '0');
  const [ttsError, setTtsError] = useState('');
  const tts = useSpeechOutput({
    onDone: () => setSpeakingId(null),
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
  const readOnlyLoaded = !!(session && (session.status === 'ENDED' || session.status === 'REPORTED'));

  // 新面试官消息完成流式后自动朗读；挂载时只登记基线不补读历史
  useEffect(() => {
    if (!autoRead || !tts.supported || readOnlyLoaded) return;
    const last = [...annotated].reverse().find((m) => m.role === 'INTERVIEWER');
    if (!last || last.streaming) return;
    const key = last.id ?? last.content;
    if (lastInterviewerRef.current === null) {
      lastInterviewerRef.current = key;
      return;
    }
    if (lastInterviewerRef.current === key) return;
    lastInterviewerRef.current = key;
    setTtsError('');
    setSpeakingId(key);
    tts.speak(last.content);
  }, [annotated, autoRead, tts.supported, readOnlyLoaded, tts.speak]);

  // 新消息与流式增量时自动滚到底部
  useEffect(() => {
    const el = listRef.current;
    if (el) el.scrollTop = el.scrollHeight;
  }, [annotated]);

  // 已结束会话只读回看：报告页"查看消息记录"入口（页面方案 4.3），
  // 不再重定向报告页（否则与该入口互相弹回）

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

  const readOnly = !!session && (session.status === 'ENDED' || session.status === 'REPORTED');
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

  return (
    <>
      <header className="chat-header">
        <div className="title">
          {title}
          {statusBadge}
        </div>
        <div className="actions">
          {tts.supported && (
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
                    if (last && !readOnlyLoaded && tts.supported) {
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

      {!readOnly && (
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
            className={`speech-btn ${listening ? 'speech-listening' : ''}`}
            onClick={listening ? stopSpeech : start}
            disabled={streaming || !supported || transcribing}
            title={supported ? '语音输入，识别后可再编辑' : '当前浏览器不支持语音输入'}
          >
            {listening ? '停止' : transcribing ? '识别中' : '语音'}
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
          {!supported && (
            <div className="speech-error">当前浏览器不支持语音输入，请改用 Chrome 或 Edge，或手动输入</div>
          )}
        </div>
      )}
    </>
  );
}
