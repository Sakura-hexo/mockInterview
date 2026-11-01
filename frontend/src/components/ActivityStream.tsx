import { useState } from 'react';
import type { ActivityItem } from '../hooks/useInterviewStream';

// Agent 活动流：气泡下方灰色小字，最近 4 条、更旧淡出、可折叠（页面方案 4.2）
// P0 事件较少（发送/生成/失败），标题为纯文字

export default function ActivityStream({ items }: { items: ActivityItem[] }) {
  const [open, setOpen] = useState(true);
  if (items.length === 0) return null;

  return (
    <div className="activity-wrap">
      <div className="activity-stream">
        <button className="activity-toggle" onClick={() => setOpen(!open)}>
          Agent 活动（{items.length}）{open ? ' ▴' : ' ▾'}
        </button>
        {open && (
          <div className="activity-items">
            {items.map((item, i) => (
              <div
                key={item.id}
                className={`activity-item${i < items.length - 1 ? ' faded' : ''}`}
              >
                <span className={`activity-dot dot-${item.state}`} />
                <span className="activity-who">{item.who}</span>
                <span>{item.text}</span>
              </div>
            ))}
          </div>
        )}
      </div>
    </div>
  );
}
