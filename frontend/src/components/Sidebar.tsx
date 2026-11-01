import { useCallback, useEffect, useState } from 'react';
import { useLocation, useNavigate } from 'react-router-dom';
import { clearToken } from '../api/http';
import { clearUsernameStored, getUsernameStored, logout } from '../api/auth';
import { listInterviews } from '../api/interview';
import type { InterviewSummary, SessionStatus } from '../types';

// 侧栏：品牌 + 新建入口 + 历史会话列表 + 退出（页面方案 3.2/4.4，视觉按 V1 定稿设计稿 02）

const STATUS_BADGE: Record<SessionStatus, { text: string; cls: string }> = {
  CREATED: { text: '未开始', cls: 'badge-gray' },
  IN_PROGRESS: { text: '进行中', cls: 'badge-blue' },
  ENDED: { text: '待生成', cls: 'badge-yellow' },
  REPORTED: { text: '已出报告', cls: 'badge-green' },
};

const DIRECTION_LABEL: Record<string, string> = {
  JAVA_BACKEND: 'Java 后端',
};

const DIFFICULTY_LABEL: Record<string, string> = {
  EASY: '简单',
  MEDIUM: '中等',
  HARD: '困难',
};

// 点击按状态路由：IN_PROGRESS → 面试页；ENDED/REPORTED → 报告页（页面方案 4.4）
function itemPath(s: InterviewSummary): string {
  return s.status === 'ENDED' || s.status === 'REPORTED'
    ? `/report/${s.sessionId}`
    : `/interview/${s.sessionId}`;
}

function relativeTime(iso: string): string {
  const t = new Date(iso).getTime();
  if (Number.isNaN(t)) return '';
  const min = Math.floor((Date.now() - t) / 60000);
  if (min < 1) return '刚刚';
  if (min < 60) return `${min}分钟前`;
  const hour = Math.floor(min / 60);
  if (hour < 24) return `${hour}小时前`;
  const day = Math.floor(hour / 24);
  if (day === 1) return '昨天';
  if (day < 7) return `${day}天前`;
  const d = new Date(t);
  return `${d.getMonth() + 1}-${d.getDate()}`;
}

function ConversationItem({
  item,
  active,
}: {
  item: InterviewSummary;
  active: boolean;
}) {
  const navigate = useNavigate();
  const badge = STATUS_BADGE[item.status] ?? STATUS_BADGE.CREATED;
  return (
    <div
      className={`conversation-item${active ? ' active' : ''}`}
      onClick={() => navigate(itemPath(item))}
    >
      <div className="conversation-title">
        <span>
          {DIRECTION_LABEL[item.direction] ?? item.direction} ·{' '}
          {DIFFICULTY_LABEL[item.difficulty] ?? item.difficulty}
        </span>
        <span className={`badge ${badge.cls}`}>{badge.text}</span>
      </div>
      <div className="conversation-meta">
        <span>{relativeTime(item.createTime)}</span>
      </div>
    </div>
  );
}

export default function Sidebar() {
  const navigate = useNavigate();
  const location = useLocation();
  const [conversations, setConversations] = useState<InterviewSummary[]>([]);

  const load = useCallback(() => {
    // 后端未启动或请求失败时列表置空；401 的清 token 与跳转由 http.ts 统一处理
    listInterviews(1, 20)
      .then((d) => setConversations(d.list))
      .catch(() => setConversations([]));
  }, []);

  // 会话列表随路由切换刷新（页面方案第 5 节）
  useEffect(() => {
    load();
  }, [load, location.pathname]);

  // 聊天页一轮 done 后经 interviews-changed 事件通知刷新（页面方案 4.2）
  useEffect(() => {
    const handler = () => load();
    window.addEventListener('interviews-changed', handler);
    return () => window.removeEventListener('interviews-changed', handler);
  }, [load]);

  const activeList = conversations.filter(
    (c) => c.status === 'IN_PROGRESS' || c.status === 'CREATED',
  );
  const endedList = conversations.filter(
    (c) => c.status === 'ENDED' || c.status === 'REPORTED',
  );

  function handleLogout() {
    // 先发注销请求再清令牌：apiFetch 组装请求头时才读得到本地 token，失败不阻塞本地登出
    logout().catch(() => {});
    clearToken();
    clearUsernameStored();
    navigate('/login');
  }

  return (
    <aside className="sidebar">
      <div className="sidebar-brand">AI 模拟面试</div>

      <button className="new-chat-btn" onClick={() => navigate('/new')}>
        ＋ 新建面试
      </button>

      <div className="conversation-list">
        <div className="sidebar-section">进行中</div>
        {activeList.map((c) => (
          <ConversationItem
            key={c.sessionId}
            item={c}
            active={location.pathname === itemPath(c)}
          />
        ))}

        <div className="sidebar-section">已结束</div>
        {endedList.map((c) => (
          <ConversationItem
            key={c.sessionId}
            item={c}
            active={location.pathname === itemPath(c)}
          />
        ))}
      </div>

      <div className="sidebar-footer">
        <span>{getUsernameStored() ?? '已登录'}</span>
        <button onClick={handleLogout}>退出</button>
      </div>
    </aside>
  );
}
