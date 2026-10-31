import { useEffect, useState } from 'react';
import { useNavigate } from 'react-router-dom';
import { listInterviews } from '../api/interview';

// 历史会话首页 /：左栏为会话入口，主区为空状态引导（页面方案 4.4，视觉按 V1 设计稿 02）
export default function HistoryPage() {
  const navigate = useNavigate();
  const [total, setTotal] = useState(0);

  useEffect(() => {
    listInterviews(1, 1)
      .then((d) => setTotal(d.total))
      .catch(() => setTotal(0));
  }, []);

  return (
    <>
      <header className="chat-header">
        <div className="title">历史会话</div>
        <div className="actions">
          <span className="badge badge-gray">共 {total} 场</span>
        </div>
      </header>

      <div className="main-body">
        <div className="empty-hint">
          <h2>开始你的第一场模拟面试</h2>
          <p>
            上传简历 + 粘贴目标岗位 JD
            <br />
            AI 面试官将基于你的简历进行多轮提问与追问
            <br />
            结束后输出结构化评分与改进建议
          </p>
          <button className="btn btn-primary" onClick={() => navigate('/new')}>
            ＋ 新建面试
          </button>
        </div>
      </div>
    </>
  );
}
