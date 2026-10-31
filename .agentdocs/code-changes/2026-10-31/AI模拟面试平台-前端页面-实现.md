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

## F4 SSE 流式面试聊天页（2026-10-31）

### 做了什么（按架构方案分层落地）

- **`src/lib/sseParser.ts`（43 行）**：SSE 解析纯函数——事件块按空行分隔、多 `data:` 行按换行 join（travelScope 防坑）、CRLF 兼容、注释行跳过、半块留 rest。**vitest 单测 8 例**：正常流、chunk 半条、跨 chunk 拼接、多 data 行、error 事件、CRLF、注释与空块
- **`src/lib/interviewPhase.ts`（55 行）**：阶段推导纯函数——`annotateMessages` 逐条标注徽章与题号（QUESTION/NEXT 推进题号、FOLLOW_UP 同题、无 action 历史消息兜底按新题）、`derivePhase` 输出顶栏进度与流式状态。**vitest 单测 6 例**
- **`src/api/interviewStream.ts`（98 行）**：fetch + ReadableStream 手写 SSE 客户端（不用 EventSource），Bearer 头、401 统一处理、流结束残余缓冲补解析、非 JSON 数据静默跳过
- **`src/api/interview.ts`**：补 `endInterview`（4.4）与 `getInterviewDetail`（4.7，session 支持 questionCount 可选字段）
- **`src/hooks/useInterviewStream.ts`（202 行）**：核心 hook——发送、SSE 订阅与卸载清理、停止（AbortController）、**刷新恢复**（详情恢复 + 最后一条是候选人时降级 3 秒轮询，用户已确认方案）、活动流条目、done 后广播 `interviews-changed` 事件
- **`src/components/MessageBubble.tsx`**：气泡组件——文字徽章、react-markdown + remark-gfm 渲染面试官内容、流式光标、metrics 小字（无 latency 不显示耗时）、错误红条
- **`src/components/ActivityStream.tsx`**：Agent 活动流，折叠 UI，最近 4 条
- **`src/pages/InterviewPage.tsx`（152 行）+ InterviewPage.css（222 行）**：完整聊天页——顶栏（方向/难度/进度/IN_PROGRESS 徽章/红色结束面试）、流式与恢复横幅、消息列表自动滚底、输入区（Enter 发送/Shift+Enter 换行/流式中禁用换停止按钮）、结束二次确认 confirm、非 IN_PROGRESS 会话重定向报告页
- **`src/components/Sidebar.tsx`**：监听 `interviews-changed` 事件刷新列表（方案 4.2 done 事件要求）
- 依赖：react-markdown 10.1.0、remark-gfm 4.0.1、vitest 5.0.5；`npm test` 脚本

### 验证记录（8081 临时 mock 全流程 E2E，含慢速流/恢复专用会话）

1. 单测 14/14 通过（两处初版测试用例笔误已修：末块缺终止空行、可选链 undefined/null）
2. 初始渲染：详情恢复第一题气泡（第 1 题徽章）、顶栏"第 1/10 题"、侧栏进行中分组
3. 追问轮：流式增量渲染 + 光标 + 提示条 + 输入禁用/停止按钮；完成后追问徽章、`tokens: 412 · 4.1s`、光标消失、输入恢复
4. **跨行 JSON 拼接**：一个 delta 的 JSON 拆两个 data 行（token 之间），渲染正确无字面 `data:` 泄漏
5. 换题轮：NEXT → 第 2 题徽章（紫）、顶栏"第 2/10 题"、前轮消息不被污染
6. 停止按钮：慢速流（700ms/段）中断 → 已到 3 段保留、光标消失、输入恢复、无错误条
7. 刷新恢复：最后一条为候选人的会话 → 恢复横幅 + 3 秒轮询 → 面试官回复自动同步、横幅消失（元素级断言）、追问徽章正确
8. 结束面试：confirm 弹窗接受 → end 接口 → 跳转 `/report/:id`

### 遇到的问题与修复

| 问题 | 处理 |
|---|---|
| **流式消息 id 冲突（真 bug）**：完成后仍保留 `STREAMING_ID`，下一轮补丁命中历史消息，导致两轮文本合流、题号多跳 | 轮次结束 finally 中换唯一 id `iv-{ts}` |
| **StrictMode 双挂载状态残留**：首次挂载设置的恢复轮询在 cleanup 被清，二次挂载判定不需恢复却未重置标志，横幅常驻 | 初始加载幂等决策：需恢复则 start，否则 stopRecovery 重置 |
| JSON 拆 data 行拆在字符串内部导致裸换行非法 | 拆分点必须在 JSON token 之间（如冒号后）——供后端 SSE 实现参考 |
| E2E 断言字符串与活动流文案撞车（"自动同步中"）误报横幅未消失 | 改为元素级断言 `querySelector('.streaming-hint')` |

### 契约缺口（待接口文档/后端确认，影响上线联调）

| 缺口 | 前端现状 |
|---|---|
| `ChatMessage` 无 `action` 字段 | 历史消息无法还原徽章与题号，兜底按新题推进；后端 SSE 需把 action 回带进落库消息 |
| `ChatMessage.role` 枚举未定义 | hook 里归一 INTERVIEWER/CANDIDATE（兼容 ASSISTANT/USER） |
| `InterviewSummary` 无 `questionCount` | 顶栏降级显示"第 N 题"，mock 提供该字段时显示"N/M" |
| start 返回的 firstQuestion 是否落入 4.7 的 messages | 前端假设必须落入（刷新恢复依赖详情渲染第一题） |

### 提交记录

- F4 提交于 `frontend` 分支（SSE 聊天页全套 + 单测 + 两个 bug 修复）

## F5 复盘报告页（2026-10-31）

### 做了什么

- **`src/lib/reportProgress.ts`（23 行）+ 单测 5 例**：轮询节奏纯函数（前 5 次 3 秒、之后退避 10 秒封顶）、阶段推导（每 2 次轮询推进一阶段：面试消息汇总 → 逐题评分 → 生成改进建议 → 汇总输出）、`formatDuration`（毫秒 → "N 分 N 秒"）
- **`src/api/http.ts`**：`apiFetch` 增加可选 `acceptCodes` 参数——报告接口 202 生成中是业务正常态，不能走"code 非 0 即抛错"通道
- **`src/api/interview.ts`**：补 `getReport`（接口文档 4.5），返回可判别联合 `GENERATING | DONE`；类型补齐 ReportScore/ReportItem/InterviewReport
- **`src/hooks/useReportPolling.ts`（67 行）**：轮询 hook——生成中按节奏调度、阶段推进、真实错误（404 等）停止轮询、卸载清理
- **`src/pages/ReportPage.tsx`（190 行）+ ReportPage.css（297 行）**：按 V1 设计稿 05/07 还原——生成中态（旋转圆环 + 四步勾选进度）、完整报告（白底评分卡大号蓝色综合分 + 一句话结论、四维纯色进度条、逐题 details 手风琴首题默认展开、蓝竖线评语引用、编号建议列表、底部等宽字体元信息）、顶栏"查看消息记录"入口
- **`src/pages/InterviewPage.tsx` 只读回看**：ENDED/REPORTED 会话不再重定向报告页（否则与"查看消息记录"入口互相弹回），改为只读模式——标题"面试记录"、状态徽章（已出报告绿/已结束黄）、隐藏结束按钮与输入区，转写正常渲染
- **动画统一**：`@keyframes` blink/spin 提升至 App.css 全局，各页 css 删除重复定义

### 验证记录（8081 临时 mock E2E）

1. 单测 19/19（新增 reportProgress 5 例）
2. 生成中态：黄色"报告生成中"徽章、智能体提示文案、阶段 0"面试消息汇总中…"运行、四步齐全
3. 两轮 3 秒轮询后自动出完整报告：大分数 7.8、顶栏"复盘报告 · Java 后端 · 困难"、四维行、3 个手风琴（首个展开显示题目/摘要/评语）、建议列表、底部"Token 消耗：12,847 · 面试时长：32 分 18 秒"
4. "查看消息记录" → 只读回看：标题"面试记录"、绿徽章"已出报告"、无结束按钮、无输入区、转写含追问徽章

### 方案偏离备注

| 偏离 | 原因 |
|---|---|
| 方案 4.3 的"继续保持"虚线框未实现 | 接口文档 InterviewReport 无对应字段（suggestions 之外无保持项数据源），已列入契约缺口 |
| 报告页底部无"模型：qwen-plus"元信息 | InterviewReport 无模型字段，同上 |
| 面试页对已结束会话从"重定向报告页"改为"只读回看" | 重定向会与方案 4.3 要求的"查看消息记录"入口形成死循环，只读模式同时满足 3.1 的"仅进行中可作答"约束 |

### 契约缺口追加（转后端）

| 缺口 | 说明 |
|---|---|
| InterviewReport 无"继续保持"字段 | 方案 4.3 的保持项无数据源，需接口文档定义或并入 suggestions |
| InterviewReport 无模型名字段 | 报告底部元信息无法显示模型，需接口文档补充 |

### 提交记录

- F5 提交于 `frontend` 分支（报告页 + 轮询 hook + 只读回看 + 全局动画统一）

## F6 走查与样式打磨（2026-10-31）

### 做了什么

- **补齐三个漏定义的样式类**（走查发现的真问题，DOM 断言测不出来）：`.btn-danger`（红底白字，结束面试按钮此前无底色）、`.btn-ghost`（透明底细边框，取消/查看消息记录按钮）、`.mono` 工具类（IN_PROGRESS 徽章此前非等宽）。均按 V1 定稿 shared.css 值补入 App.css
- **流式提示条计数**：按 V1 设计稿 06 补"已生成 N 字 · Ns"——字数取当前流式消息内容长度，用时由 hook 新增的 `streamStart` 时间戳推导，流结束归零
- **`src/vite-env.d.ts`**：F0 漏建的 Vite 模板标配文件（CSS 副作用导入类型声明），修复 `tsc --noEmit` 的 6 个 TS2882 错误
- 新增 `npx tsc --noEmit` 通过（严格模式全量类型检查零错误）

### 验证记录

1. 单测 19/19、`tsc --noEmit` 零错误
2. 计算样式比对（8081 临时 mock）：`.btn-danger` 背景 `rgb(239,68,68)` 白字；`.badge-blue.mono` 等宽字体生效；`.btn-ghost` 边框 `#d5dbe6` 透明底
3. 流式计数：发送后提示条显示"面试官正在回复…已生成 38 字 · 0s"，随增量增长，流结束消失
4. 四页走查：登录页表单完整；首页侧栏数据 + 空态引导；新建页表单完整；**报告页/面试页错误态**（未知会话 → "报告加载失败"/"会话加载失败"）——加载态与错误态此前已在 F4/F5 实现，本步补齐走查覆盖

### 遗留决策项（非代码，待用户/架构师）

- 页面方案 3.2 侧栏"难度筛选下拉"：定稿设计稿无此元素，F2 起按设计稿未实现，需拍板
- 侧栏列表项"第 N/M 题 / 综合 X.X"：等接口文档 4.6 补字段（F2 已记录）

### 提交记录

- F6 提交于 `frontend` 分支（按钮样式补齐 + 流式计数 + 类型检查修复）

## F7 生产构建集成 Spring Boot static（2026-10-31）

### 做了什么

- **`frontend/vite.config.ts`**：`build.outDir` 直指 `../backend/src/main/resources/static`（方案第 2 节"产物拷到 static"的零拷贝实现），`emptyOutDir` 显式开启
- **`.gitignore`**：忽略 `backend/src/main/resources/static/`（构建产物不入库，部署时构建生成）
- **SPA 路由回退**：属于后端 Java 配置，为避免与并行后端会话混提交，本步不直接改后端代码，交接片段见下节，由后端会话在步骤 7 落地

### 验证记录

1. `npm run build` 成功：294 模块，index.html 0.4kB + CSS 13.87kB（gzip 3.3）+ JS 446.98kB（gzip 139.57，含 react/react-router/react-markdown），产物落 `backend/src/main/resources/static/`，git status 干净（忽略生效）
2. `vite preview`（4173）生产包冒烟：`/login` 完整渲染；静态资源 JS/CSS 均 200；**直路由 `/interview/abc` 返回 200**，应用启动后 RequireAuth 无 token 正确弹回 `/login`——静态资源、SPA 回退、客户端守卫全链路正常

### 交接后端会话：SPA 路由回退配置（步骤 7 落地）

验收项"刷新任意路由不 404"需要 Spring 把非 `/api` 的前端路由请求转发到 index.html。推荐实现（显式路由列表，不用通配符，避免与 REST 映射冲突）：

```java
// backend/src/main/java/com/sakura/interview/web/SpaForwardController.java
package com.sakura.interview.web;

import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;

/** 前端 SPA 路由回退：刷新 /login /new /interview/x /report/x 不 404（前端页面方案验收项） */
@Controller
public class SpaForwardController {
    @GetMapping({"/", "/login", "/new", "/interview/**", "/report/**"})
    public String spa() {
        return "forward:/index.html";
    }
}
```

前置条件：后端重建重启后 static 产物即在 classpath 生效。`/api/**` 由 REST 控制器处理，不受影响；生产构建命令为 `cd frontend && npm run build`。

### 提交记录

- F7 提交于 `frontend` 分支（构建目录集成 + gitignore + 交接文档）；"8080 直接服务 + 刷新不 404"的最终验收待后端会话重建后补录
