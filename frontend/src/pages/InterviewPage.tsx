import { KeyboardEvent, useEffect, useRef, useState } from 'react';
import { useNavigate, useParams } from 'react-router-dom';
import { endInterview } from '../api/interview';
import { ApiError } from '../api/http';
import { useInterviewStream } from '../hooks/useInterviewStream';
import MessageBubble from '../components/MessageBubble';
import ActivityStream from '../components/ActivityStream';
import './InterviewPage.css';

const DIRECTION_LABEL: Record<string, string> = { JAVA_BACKEND: 'Java 后端' };
const DIFFICULTY_LABEL: Record<string, string> = { EASY: '简单', MEDIUM: '中等', HARD: '困难' };

// 面试聊天页 /interview/:id（页面方案 4.2，视觉按 V1 定稿设计稿 04/06）
export default function InterviewPage() {
  const { id = '' } = useParams();
  const navigate = useNavigate();
  const {
    session,
    annotated,
    phase,
    streaming,
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
          {!readOnly && (
            <button className="btn btn-danger" onClick={handleEnd} disabled={ending}>
              {ending ? '结束中…' : '结束面试'}
            </button>
          )}
        </div>
      </header>

      {!readOnly && (streaming || recovering) && (
        <div className="streaming-hint">
          <span className="pulse" />
          <span>
            {recovering
              ? '面试官回复生成中，自动同步中（每 3 秒）…'
              : '面试官正在回复…'}
          </span>
        </div>
      )}

      <div className="main-body" ref={listRef}>
        <div className="message-list">
          {loadError && <div className="interview-error">{loadError}</div>}
          {endError && <div className="interview-error">{endError}</div>}
          {annotated.map((m) => (
            <MessageBubble key={m.id ?? m.content} message={m} />
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
        </div>
      )}
    </>
  );
}
