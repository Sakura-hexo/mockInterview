// 统一请求封装：token 存取、Bearer 头、ApiError、401 统一处理（前端架构方案第 3 节）
// 页面与组件禁止直接 fetch，一律走本文件

export class ApiError extends Error {
  constructor(
    public status: number,
    message: string,
  ) {
    super(message);
    this.name = 'ApiError';
  }
}

const TOKEN_KEY = 'APP_TOKEN';

export function getToken(): string | null {
  return localStorage.getItem(TOKEN_KEY);
}

export function setToken(token: string): void {
  localStorage.setItem(TOKEN_KEY, token);
}

export function clearToken(): void {
  localStorage.removeItem(TOKEN_KEY);
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
  const token = getToken();
  if (token) headers.set('Authorization', `Bearer ${token}`);
  // FormData 不能手动设 Content-Type，需让浏览器自动生成 multipart 边界
  if (typeof init?.body === 'string' && !headers.has('Content-Type')) {
    headers.set('Content-Type', 'application/json');
  }

  const res = await fetch(path, { ...init, headers });

  if (res.status === 401) {
    // 登录页自身的验证请求不清跳转，仅由调用方展示错误
    clearToken();
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
