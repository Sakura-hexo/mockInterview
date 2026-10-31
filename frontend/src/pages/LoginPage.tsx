import { FormEvent, useState } from 'react';
import { useNavigate } from 'react-router-dom';
import { ApiError, setToken } from '../api/http';
import { listInterviews } from '../api/interview';

// 令牌输入页 /login（页面方案 4.5，视觉按 V1 定稿设计稿 01）
export default function LoginPage() {
  const navigate = useNavigate();
  const [token, setTokenInput] = useState('');
  const [error, setError] = useState('');
  const [loading, setLoading] = useState(false);

  async function handleSubmit(e: FormEvent) {
    e.preventDefault();
    const trimmed = token.trim();
    if (!trimmed || loading) return;

    setLoading(true);
    setError('');
    setToken(trimmed);
    try {
      // 提交后用一次列表请求验证 token（页面方案 4.5）
      await listInterviews(1, 1);
      navigate('/');
    } catch (err) {
      if (err instanceof ApiError && err.status === 401) {
        setError('令牌无效，请检查后重试（401 Unauthorized）');
      } else {
        setError('无法连接后端服务，请确认 8080 端口已启动');
      }
    } finally {
      setLoading(false);
    }
  }

  return (
    <div className="login-page">
      <form className="login-card" onSubmit={handleSubmit}>
        <h2>AI 模拟面试平台</h2>
        <div className="sub">多智能体技术面试练习系统</div>

        <label>
          访问令牌（APP_TOKEN）
          <input
            type="password"
            placeholder="请输入令牌"
            value={token}
            onChange={(e) => setTokenInput(e.target.value)}
            autoFocus
          />
        </label>

        {error && <div className="login-error">{error}</div>}

        <button type="submit" disabled={loading || !token.trim()}>
          {loading ? '验证中…' : '进入系统'}
        </button>

        <div className="login-tip">
          令牌从后端 <code>.env</code> 文件的 <code>APP_TOKEN</code> 复制
          <br />
          本地部署 · 仅限本机访问
        </div>
      </form>
    </div>
  );
}
