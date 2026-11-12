import ReactMarkdown from 'react-markdown';
import remarkGfm from 'remark-gfm';
import type { AnnotatedMessage } from '../lib/interviewPhase';
import './MessageBubble.css';

// 面试官气泡：徽章 + Markdown 正文 + 流式光标 + metrics 小字 + 错误红条
// 候选人气泡：蓝底右侧（页面方案 4.2，视觉按 V1 定稿设计稿 04）

// 后端时间为带偏移 ISO（+08:00）；解析后按浏览器本地时区显示 HH:mm
function clockOf(iso?: string): string {
  if (!iso) return '';
  const d = new Date(iso);
  return Number.isNaN(d.getTime())
    ? ''
    : d.toLocaleTimeString('zh-CN', { hour: '2-digit', minute: '2-digit' });
}

export default function MessageBubble({
  message,
  onToggleSpeak,
  speaking,
  ttsSupported,
}: {
  message: AnnotatedMessage;
  onToggleSpeak?: () => void;
  speaking?: boolean;
  ttsSupported?: boolean;
}) {
  const clock = clockOf(message.time);
  if (message.role === 'CANDIDATE') {
    return (
      <div className="message-row user">
        <div className="bubble user">
          {message.content}
          {clock && <div className="bubble-stats">{clock}</div>}
        </div>
      </div>
    );
  }

  return (
    <div className="message-row assistant">
      <div className="bubble assistant">
        {message.badge && (
          <div className="bubble-meta">
            <span className={`bubble-tag ${message.badge.cls}`}>{message.badge.text}</span>
            {clock && <span className="bubble-time">{clock}</span>}
            {onToggleSpeak && !message.streaming && (
              <button
                type="button"
                className={`tts-btn ${speaking ? 'tts-speaking' : ''}`}
                onClick={onToggleSpeak}
                disabled={!ttsSupported}
                title={ttsSupported ? (speaking ? '停止朗读' : '朗读本题') : '当前浏览器不支持语音播放'}
              >
                {speaking ? '停止' : '读题'}
              </button>
            )}
          </div>
        )}
        <div className="bubble-content">
          <ReactMarkdown remarkPlugins={[remarkGfm]}>{message.content || ' '}</ReactMarkdown>
          {message.streaming && <span className="cursor">▌</span>}
        </div>
        {message.error && <div className="bubble-error">{message.error}</div>}
        {(message.tokens !== undefined || message.latencyMs !== undefined) && (
          <div className="bubble-stats">
            {message.tokens !== undefined && `tokens: ${message.tokens}`}
            {message.tokens !== undefined && message.latencyMs !== undefined && ' · '}
            {message.latencyMs !== undefined && `${(message.latencyMs / 1000).toFixed(1)}s`}
          </div>
        )}
      </div>
    </div>
  );
}
