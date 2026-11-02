// 认证 API：注册/登录/登出（认证方案 2026-11-02 Cookie 模式修订）
// 会话令牌只存 HttpOnly Cookie，前端只维护登录标志与用户名展示（存取在 http.ts）
import { apiFetch } from './http';

export interface AuthResult {
  username: string;
}

export function login(username: string, password: string): Promise<AuthResult> {
  return apiFetch('/api/auth/login', {
    method: 'POST',
    body: JSON.stringify({ username, password }),
  });
}

// 注册即登录，服务端同时下发会话 Cookie
export function register(username: string, password: string): Promise<AuthResult> {
  return apiFetch('/api/auth/register', {
    method: 'POST',
    body: JSON.stringify({ username, password }),
  });
}

// 登出只负责服务端会话注销与 Cookie 清除，本地登录态由调用方清理
export function logout(): Promise<void> {
  return apiFetch('/api/auth/logout', { method: 'POST' });
}
