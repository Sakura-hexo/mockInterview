import { FormEvent, useState } from 'react';
import { useNavigate } from 'react-router-dom';
import { ApiError, setToken } from '../api/http';
import { login, register, setUsernameStored } from '../api/auth';
import './LoginPage.css';

// 登录/注册页 /login（认证改造方案 2026-11-01，视觉沿用 V1 定稿设计稿 01）
type Mode = 'login' | 'register';

const ERROR_TEXT: Record<number, string> = {
  401: '用户名或密码错误',
  409: '用户名已被占用，换一个试试',
  400: '参数不符合要求',
  429: '失败次数过多，请 1 分钟后再试',
};

export default function LoginPage() {
  const navigate = useNavigate();
  const [mode, setMode] = useState<Mode>('login');
  const [username, setUsername] = useState('');
  const [password, setPassword] = useState('');
  const [showPassword, setShowPassword] = useState(false);
  const [error, setError] = useState('');
  const [loading, setLoading] = useState(false);

  function switchMode(next: Mode) {
    setMode(next);
    setError('');
  }

  async function handleSubmit(e: FormEvent) {
    e.preventDefault();
    if (loading) return;
    const name = username.trim();
    if (name.length < 2 || name.length > 20) {
      setError('请输入 2 到 20 位用户名');
      return;
    }
    if (!password) {
      setError('请输入密码');
      return;
    }
    if (mode === 'register' && password.length < 8) {
      setError('密码至少 8 位');
      return;
    }

    setLoading(true);
    setError('');
    try {
      const result =
        mode === 'login' ? await login(name, password) : await register(name, password);
      setToken(result.token);
      setUsernameStored(result.username);
      navigate('/');
    } catch (err) {
      if (err instanceof ApiError) {
        setError(ERROR_TEXT[err.status] ?? err.message);
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

        <div className="mode-tabs">
          <button
            type="button"
            className={mode === 'login' ? 'active' : ''}
            onClick={() => switchMode('login')}
          >
            登录
          </button>
          <button
            type="button"
            className={mode === 'register' ? 'active' : ''}
            onClick={() => switchMode('register')}
          >
            注册
          </button>
        </div>

        <label>
          用户名
          <input
            type="text"
            placeholder="2 到 20 位字符"
            value={username}
            onChange={(e) => setUsername(e.target.value)}
            autoComplete="username"
            autoFocus
          />
        </label>

        <label>
          密码
          <div className="field-row">
            <input
              type={showPassword ? 'text' : 'password'}
              placeholder={mode === 'register' ? '至少 8 位' : '请输入密码'}
              value={password}
              onChange={(e) => setPassword(e.target.value)}
              autoComplete={mode === 'login' ? 'current-password' : 'new-password'}
            />
            <button
              type="button"
              className="show-toggle"
              onClick={() => setShowPassword((v) => !v)}
            >
              {showPassword ? '隐藏' : '显示'}
            </button>
          </div>
        </label>

        {error && <div className="login-error">{error}</div>}

        <button
          type="submit"
          disabled={loading || !username.trim() || !password}
        >
          {loading
            ? mode === 'login'
              ? '登录中…'
              : '注册中…'
            : mode === 'login'
              ? '进入系统'
              : '注册并进入'}
        </button>

        <div className="login-tip">
          注册即登录，无需邮箱验证
          <br />
          自部署开源项目 · 面试数据只保存在你自己的机器上
        </div>
      </form>
    </div>
  );
}
