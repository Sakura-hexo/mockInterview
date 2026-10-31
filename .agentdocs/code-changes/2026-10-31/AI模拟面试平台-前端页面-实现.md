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

## F2 侧栏与路由骨架（2026-10-31）

### 做了什么

- **`src/components/Sidebar.tsx`**：品牌区、"＋ 新建面试"虚线按钮、按状态分组（进行中 / 已结束）的会话列表（数据来自 `listInterviews(1,20)`，路由切换时刷新）、底部"本机模式 + 退出"（清 token 跳登录）。列表项两行：`方向 · 难度` + 状态徽章、相对时间。点击按状态路由（IN_PROGRESS/CREATED → 面试页；ENDED/REPORTED → 报告页），当前会话高亮。枚举→中文映射与相对时间（刚刚/N分钟前/N小时前/昨天/N天前/M-D）为组件内纯函数
- **`src/pages/HistoryPage.tsx`**：顶栏"历史会话 + 共 N 场徽章"（total 来自 `listInterviews(1,1)`）+ 主区空状态引导（纯文字 + 新建按钮，按 V1 设计稿 02）
- **路由骨架**（App.tsx 改为嵌套路由）：`RequireAuth` 与 `AppLayout`（Sidebar + Outlet）作为路径无关布局路由，`/`、`/new`、`/interview/:id`、`/report/:id` 全部挂载；F3-F5 三个页面先以占位组件存在；未知路由重定向 `/`
- **App.css**：按 V1 定稿 shared.css 像素级还原布局样式（侧栏 260px `#1e2a3a`、会话项 hover/active、五个状态徽章、顶栏、主区 `#f5f6fa`、空态、按钮），登录样式保持

### 验证记录（后端未启动，列表为空为预期）

1. 设 token 访问 `/` → 侧栏（品牌/新建/分组/退出）+ 顶栏"共 0 场" + 空态引导完整渲染
2. 点侧栏"＋ 新建面试" → `/new` 占位页
3. `/interview/abc`、`/report/abc` → 对应占位页
4. 未知路由 `/whatever/xyz` → 重定向 `/`
5. 样式与 V1 定稿计算值比对：侧栏 260px/`#1e2a3a`、主区 `#f5f6fa`、顶栏白底、`badge-gray #f1f5f9`、空态标题 `#5a6b82`、字体栈一致，全部通过
6. 点"退出" → 清 token 回 `/login`

### 方案与契约差异备注（待架构师决策）

| 差异 | 现状 |
|---|---|
| 页面方案 3.2 要求侧栏底部有"难度筛选下拉"，V1 定稿设计稿无此元素 | 按设计稿实现（本机模式 + 退出），筛选未做 |
| 页面方案 4.4 要求列表项显示"第 N/M 题 或 综合 X.X"，接口文档 InterviewSummary 无题号与分数字段 | 第二行暂只显示相对时间；需接口文档 4.6 补充字段或改为前端推导 |

### 遇到的问题

| 问题 | 处理 |
|---|---|
| 后台标签页 Playwright click 动作受限（F1 已知问题） | 交互验证统一用 evaluate 派发 `el.click()`，React onClick 正常触发 |

### 提交记录

- F2 提交于 `frontend` 分支（侧栏 + 首页空态 + 路由骨架 + 布局样式）

## F3 新建面试表单（2026-10-31）

### 做了什么

- **`src/api/interview.ts`**：补 `createInterview`（接口文档 4.1，resumeText 走 JSON、resumeFile 走 FormData 二选一）与 `startInterview`（4.2，返回 firstQuestion/focusPoints）
- **`src/api/http.ts` 修复**：`Content-Type: application/json` 只对字符串 body 设置——FormData 必须由浏览器自动生成 multipart 边界，原实现会让文件上传请求带错误头
- **`src/types.ts`**：补 `SessionConfig`（接口文档第 3 节）
- **`src/pages/NewInterviewPage.tsx`**（249 行）：完整表单按 V1 设计稿 03 还原。简历二选一互斥（选文件禁用粘贴框、粘贴文本禁用上传框，已选文件可"移除"）；PDF 5MB 前置校验；简历与 JD 必填校验；错误条显示在表单顶部；提交按钮请求中禁用并显示"创建中…"；成功后 `createInterview → startInterview → navigate('/interview/:id')`（页面方案 4.1 顺序）
- **样式拆分**（对方案"App.css 单文件"表述的偏离，按 300 行红线执行）：登录样式移至 `LoginPage.css`，表单样式新建 `NewInterviewPage.css`，`App.css` 只留全局布局（196 行）。radio 选中态用 `:has(input:checked)` 实现且无需 htmlFor 绑定
- **`vite.config.ts` 修复**：代理目标 `localhost:8080` 改为 `127.0.0.1:8080`

### 验证记录（mock 成功路径 + 真后端错误路径）

1. 表单结构对照设计稿完整（全部区块/标签/radio 含禁用态与默认选中/双取消入口/提交按钮）
2. 空表单提交 → "请上传 PDF 简历，或粘贴简历纯文本"
3. 粘贴简历文本后上传框进入禁用态（互斥生效）
4. 只填简历不填 JD → "请填写目标岗位 JD"
5. 成功路径：临时将代理指向 8081 mock（create 返回 sessionId=mock-123、start 返回第一题）→ 填表提交 → 跳转 `/interview/mock-123` 落在 F4 占位页 ✓（mock 文件已删）
6. 错误路径：对真后端提交 → 代理层故障显示 502 错误条、页面停留（当时根因见下）

### 遇到的问题

| 问题 | 处理 |
|---|---|
| Vite 代理 target 写 `localhost:8080`，Node 先解析到 IPv6 `::1`，连接被拒（ECONNREFUSED），代理返回 502——并非后端问题 | target 改 `127.0.0.1:8080`，问题消失；已写入配置注释防止回退 |
| FormData 请求被 http.ts 误设 `Content-Type: application/json` | 修复为仅字符串 body 设置该头 |
| 共享浏览器标签页上有并行活动（用户操作/后端会话），token 与页面状态偶发变化 | 验证改为单次调用内完成"设 token→填表→提交→断言"，减少干扰窗口 |

### 环境观察（供后端会话参考）

- 真后端 `GET /api/interviews` 已返回 200（无鉴权拦截），前后端联调条件正在形成
- 真后端健康检查 db 组件 DOWN（MySQL 连不上），由后端会话跟进

### 提交记录

- F3 提交于 `frontend` 分支（新建面试表单 + create/start 接入 + 样式拆分 + 代理修复）
