// 认证 API：注册/登录/登出 + 本地用户名存取（认证改造方案 2026-11-01 第 2.3 节）
import { apiFetch } from './http';

export interface AuthResult {
  token: string;
  username: string;
}

const USERNAME_KEY = 'APP_USERNAME';

export function getUsernameStored(): string | null {
  return localStorage.getItem(USERNAME_KEY);
}

export function setUsernameStored(username: string): void {
  localStorage.setItem(USERNAME_KEY, username);
}

export function clearUsernameStored(): void {
  localStorage.removeItem(USERNAME_KEY);
}

export function login(username: string, password: string): Promise<AuthResult> {
  return apiFetch('/api/auth/login', {
    method: 'POST',
    body: JSON.stringify({ username, password }),
  });
}

// 注册即登录，成功直接返回会话令牌
export function register(username: string, password: string): Promise<AuthResult> {
  return apiFetch('/api/auth/register', {
    method: 'POST',
    body: JSON.stringify({ username, password }),
  });
}

// 登出只负责服务端会话注销，本地凭据由调用方清理
export function logout(): Promise<void> {
  return apiFetch('/api/auth/logout', { method: 'POST' });
}
