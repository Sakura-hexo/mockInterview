// 统一请求封装：登录态存取、ApiError、401 统一处理（前端架构方案第 3 节）
// 页面与组件禁止直接 fetch，一律走本文件
// 会话凭据在 HttpOnly Cookie 里，本文件与令牌本体零接触（认证方案 2026-11-02 Cookie 模式）

export class ApiError extends Error {
  constructor(
    public status: number,
    message: string,
  ) {
    super(message);
    this.name = 'ApiError';
  }
}

const LOGGED_IN_KEY = 'APP_LOGGED_IN';
const USERNAME_KEY = 'APP_USERNAME';

export function isLoggedIn(): boolean {
  return localStorage.getItem(LOGGED_IN_KEY) === '1';
}

export function setLoggedIn(): void {
  localStorage.setItem(LOGGED_IN_KEY, '1');
}

// 401 或退出时统一清除：登录标志与用户名都不是凭据，泄露无风险
export function clearAuth(): void {
  localStorage.removeItem(LOGGED_IN_KEY);
  localStorage.removeItem(USERNAME_KEY);
}

export function getUsernameStored(): string | null {
  return localStorage.getItem(USERNAME_KEY);
}

export function setUsernameStored(username: string): void {
  localStorage.setItem(USERNAME_KEY, username);
}

// 统一响应体 { code, message, data }，code 非 0 表示业务错误（接口文档第 1 节）
interface Envelope<T> {
  code: number;
  message: string;
  data: T;
}

// acceptCodes：响应体 code 非 0 但业务上可接受的码（如报告接口的 202 生成中），
// 命中时照常返回 data，由调用方自行判别结构
export async function apiFetch<T>(
  path: string,
  init?: RequestInit,
  acceptCodes?: number[],
): Promise<T> {
  const headers = new Headers(init?.headers);
  // FormData 不能手动设 Content-Type，需让浏览器自动生成 multipart 边界
  if (typeof init?.body === 'string' && !headers.has('Content-Type')) {
    headers.set('Content-Type', 'application/json');
  }

  // 同源自动携带 HttpOnly 会话 Cookie，显式声明防止未来跨域误带
  const res = await fetch(path, { ...init, headers, credentials: 'same-origin' });

  if (res.status === 401) {
    // 登录页自身的验证请求不清跳转，仅由调用方展示错误
    clearAuth();
    if (window.location.pathname !== '/login') {
      window.location.assign('/login');
    }
    throw new ApiError(401, '未认证');
  }
  if (!res.ok) {
    throw new ApiError(res.status, `请求失败（${res.status}）`);
  }

  const body = (await res.json()) as Envelope<T>;
  if (body.code !== 0 && !acceptCodes?.includes(body.code)) {
    throw new ApiError(body.code, body.message || '业务错误');
  }
  return body.data;
}
