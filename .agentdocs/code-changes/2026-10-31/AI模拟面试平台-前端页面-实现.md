# AI 模拟面试平台 前端页面 实现

作者:前端开发 AI（ZCode）| 创建时间:2026-10-31

对应计划：`plans/2026-10-29/AI模拟面试平台-前端页面-优化方案.md`（页面清单、视觉定稿、F0-F7 步骤）与 `plans/2026-10-30/AI模拟面试平台-前端架构-优化方案.md`（工程分层）。本文件按 F 步骤迭代追加记录。

## F0 工程初始化（2026-10-31）

### 做了什么

- **分支策略**：按需求文档 7.1 与技术方案第 7 节既定决策，新建 `frontend` 分支开发，与后端 main 分支分别提交
- **设计稿入库**：V1 定稿、V2/V3 否决存档三版设计稿与 serve.js 预览服务器首次纳入版本管理；原 `frontend/index.html`（设计稿版本入口页）改名 `mockups.html`，把 `index.html` 让给 Vite 工程入口，serve.js 根路径映射与 V3 页回链同步修改
- **工程文件**：`package.json`、`vite.config.ts`（5173 端口，`/api` 代理到 `http://localhost:8080`）、`tsconfig.json`（strict）、`index.html`（Vite 入口）、`src/main.tsx`、`src/App.tsx`（hello world）、`src/index.css`（reset + V1 字体栈与底色）、`src/App.css`（占位）
- **依赖版本**（npm registry 实查）：Vite 8.3.5、React 19.3.1、@vitejs/plugin-react 6.1.2、TypeScript 7.0.2；react-router-dom 按计划留到 F2 引入
- **.gitignore**：追加 `node_modules/`、`dist/`
- **方案文档同步**：前端页面方案 6.1 节补记 V3 否决（2026-10-31 提案当日否决，字体观感不佳）与 V1 复审维持；第 7 节工程结构补 v3 存档目录

### 验证记录

- `npm install`：24 个包，36 秒，无 peer 依赖冲突（npmmirror 镜像）
- `npm run dev`：Vite 5173 启动，`curl /` 返回带 `/src/main.tsx` 的入口 HTML，`curl /src/App.tsx` 返回编译产物（react-refresh 注入正常）
- 浏览器 DOM 快照：`heading "AI 模拟面试平台"` 与两行说明文字成功挂载，React 渲染正常
- 设计稿预览：`node frontend/serve.js` 重启后根路径正确映射 `mockups.html`（200），V1 导航页正常（200）

### 遇到的问题

| 问题 | 处理 |
|---|---|
| Vite 要求根目录 index.html 作工程入口，与设计稿版本入口页冲突 | 设计稿入口改名 `mockups.html`，Vite 入口接管 `index.html` |
| git 推送 GitHub 失败 | git 对 github.com 配置了 `http://127.0.0.1:7890` 代理，代理软件未运行导致 443 连不通；远程仓库本身配置无误，待代理启动后推送 |

### 提交记录

- `frontend` 分支首次提交（F0 + 设计稿三版 + 方案文档修订）；推送待 GitHub 代理恢复

## F1 登录与路由守卫（2026-10-31）

### 做了什么

- **依赖**：react-router-dom 7.18.5（npm 实查后安装）
- **`src/api/http.ts`**：token 存取（localStorage 键 `APP_TOKEN`）、`apiFetch` 统一封装（自动附 Bearer 头、非 2xx 抛 `ApiError` 带 status、401 清 token 且非登录页时跳 `/login`、统一解包响应体 `data` 字段并校验 `code` 非 0）
- **`src/api/interview.ts`**：F1 仅实现登录验证所需的 `listInterviews`（接口文档 4.6），其余六方法随 F3-F5 补充
- **`src/types.ts`**：`SessionStatus` 状态机与 `InterviewSummary`（接口文档第 2/3 节）
- **`src/pages/LoginPage.tsx`**：按 V1 定稿设计稿 01 还原——写入 localStorage 后调 `listInterviews(1,1)` 验证，401 显示"令牌无效，请检查后重试（401 Unauthorized）"，网络异常显示"无法连接后端服务"；按钮在空值/请求中禁用
- **`src/App.tsx`**：`BrowserRouter` 路由骨架 + `RequireAuth` 守卫（无 token 跳 `/login`）+ `/` 首页占位（F2 移除）+ 未知路由重定向 `/`
- **`src/App.css`**：登录页样式按 V1 设计稿 shared.css 像素级还原（380px 白卡片、12px 圆角、等宽字体输入框、主蓝按钮）

### 验证记录（mock 后端 E2E，真后端联调待步骤 7）

后端 REST 是步骤 7 才实现，F1 验收"错 token 401、对 token 进首页"用临时 mock 后端（系统临时目录，验证后已删除）跑通：`Bearer good_token` 返回 200 统一响应体，其余返回 401，走 Vite `/api` 代理。

1. 无 token 访问 `/` → 重定向 `/login`，表单完整渲染，空值时按钮禁用
2. 错 token 提交 → 红字"令牌无效，请检查后重试（401 Unauthorized）"（通过 Vite 代理命中 mock 401）
3. 对 token 提交 → 跳转 `/` 显示首页占位，`localStorage.APP_TOKEN` 已写入
4. 清 token 再访问 `/` → 踢回 `/login`

### 遇到的问题

| 问题 | 处理 |
|---|---|
| IAB 后台标签页上 Playwright 的 click/Enter 派发受限（点击超时、Enter 不触发隐式提交） | 表单逻辑用 `requestSubmit()` 触发 submit 事件完成验证，事件链完好；真实用户的鼠标/键盘为原生事件不受影响 |
| 本会话 evaluate 封装按单表达式求值，`() => fn` 字符串不执行 | 统一改用 IIFE 字符串 `(function(){...})()` |

### 提交记录

- F1 提交于 `frontend` 分支（登录页 + API 层 + 路由守卫）；推送待 GitHub 代理恢复
