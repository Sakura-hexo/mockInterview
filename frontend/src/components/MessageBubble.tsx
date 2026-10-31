import ReactMarkdown from 'react-markdown';
import remarkGfm from 'remark-gfm';
import type { AnnotatedMessage } from '../lib/interviewPhase';

// 面试官气泡：徽章 + Markdown 正文 + 流式光标 + metrics 小字 + 错误红条
// 候选人气泡：蓝底右侧（页面方案 4.2，视觉按 V1 定稿设计稿 04）

export default function MessageBubble({ message }: { message: AnnotatedMessage }) {
  if (message.role === 'CANDIDATE') {
    return (
      <div className="message-row user">
        <div className="bubble user">
          {message.content}
          {message.time && <div className="bubble-stats">{message.time}</div>}
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
            {message.time && <span className="bubble-time">{message.time}</span>}
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
