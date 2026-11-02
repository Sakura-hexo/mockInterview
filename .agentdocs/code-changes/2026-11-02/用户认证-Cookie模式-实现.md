# AI 模拟面试平台 用户认证 Cookie 模式 实现

作者:Sakura | 创建时间:2026-11-02 11:05

对应方案：`.agentdocs/plans/2026-11-02/用户认证-Cookie模式-优化方案.md`（用户拍板"安全第一，出完直接做"）。HttpOnly Cookie 取代 Bearer + localStorage 令牌，令牌本体不再到达前端 JS。

## 一 后端改动

| 文件 | 改动 |
|---|---|
| AuthController | 登录注册经 Set-Cookie 下发 `INTERVIEW_SESSION`（HttpOnly; SameSite=Strict; Path=/api; Max-Age 86400；Secure 由配置控制）；响应体改为 `data: {username}` 不含令牌；登出以同属性清 Cookie；新增 `auth.cookie.secure` 构造注入 |
| AuthInterceptor | 从 Cookie 头取会话令牌（Bearer 分支移除），校验逻辑与 401 信封不变 |
| application.yml | 新增 `auth.cookie.secure: ${AUTH_COOKIE_SECURE:false}`（HTTPS 反代部署置 true） |
| InterviewApiTest | 全部请求由 Bearer 头改为 `.cookie(...)`；register 助手断言 `HttpOnly` 为真后返回 Cookie 对象 |

## 二 前端改动

| 文件 | 改动 |
|---|---|
| http.ts | 移除令牌存取与 Authorization 头；新增 `APP_LOGGED_IN` 标志与 `APP_USERNAME` 存取、`clearAuth` 统一清理；fetch 显式 `credentials: 'same-origin'` |
| auth.ts | 三接口调用保留；用户名存取挪至 http.ts；AuthResult 只含 username |
| App.tsx | RequireAuth 改查登录标志（真实凭据校验由后端 Cookie 会话兜底） |
| LoginPage.tsx | 成功置标志与用户名；顺带清掉 Bearer 时代的孤儿键 `APP_TOKEN` |
| Sidebar.tsx | 退出改为 logout 接口 + `clearAuth()` |
| interviewStream.ts | SSE fetch 去掉 Authorization 头，显式 same-origin 携带 Cookie |

## 三 验证记录

- 后端：`mvn test -Dtest="AuthServiceTest,InterviewApiTest"` 20/20 通过（register 助手内嵌 HttpOnly 断言）；编译零告警
- 前端：`tsc --noEmit` 零报错；单测 19/19；全仓 grep 无 getToken/setToken/clearToken/Authorization 残留
- E2E（8081 Cookie 版 mock，vite 代理临时切换后还原）：
  1. 注册成功进入 `/`，侧栏显示用户名，`APP_LOGGED_IN`/`APP_USERNAME` 落位
  2. **HttpOnly 逐字验证：登录后 `document.cookie` 读不到 `INTERVIEW_SESSION`**，同时侧栏列表加载成功证明浏览器确实在携带 Cookie
  3. 退出：回 `/login`，登录标志与用户名清空
  4. 重新登录（正确填表）进入 `/`
  5. 伪造登录标志但无有效 Cookie → 访问 `/` 被 401 打回 `/login` 并清标志（等价 Cookie 过期场景）
- 真机复验（后端会话重启后实测）：注册返回 `Set-Cookie: INTERVIEW_SESSION=...; Path=/api; Max-Age=86400; HttpOnly; SameSite=Strict`，响应体只有 username 无令牌；带 Cookie 访问列表 200、无 Cookie 401、旧 Bearer 头 401（通道确认已死）。冒烟账号 `cookiesmoke` 留在 t_user，可忽略

## 四 踩坑记录

- 登出后 LoginPage 重新挂载输入框为空，E2E 脚本直接 requestSubmit 撞上客户端校验——测试脚本时序问题非产品缺陷
- mvn 不在 PATH，实际位于 `D:\apache-maven-3.9.13\bin\mvn`
- 后端须从仓库根目录启动：EnvKeys 定位 `backend/.env` 为相对路径，在 backend/ 目录内启动会报"未找到 MYSQL_PASSWORD"（本会话实测踩坑）
- 接口文档编辑时被并行会话的署名替换提交抢先触碰，需重读后再改（内容无冲突）

## 五 待办与移交

- 后端已由后端会话重启并真机复验通过；用户旧登录态（localStorage 存的令牌）已无意义，下次打开页面需重新登录一次，属预期
- README 部署章节（开源化清单项）落地时补 `AUTH_COOKIE_SECURE` 与 HTTPS 反代说明
