# AI 模拟面试平台 接口文档

作者:Sakura | 创建时间:2026-10-29

本文档定义 REST 接口边界与数据结构，供开发步骤 7 实现全量接口、步骤 6 提前定义报告结构。SSE 流式协议在步骤 7 落地时如需微调，回架构师确认后修订本文档。

> 2026-10-31 修订：第 1 节补充跨域接入策略；4.3 补充 error 事件与断线恢复契约；新增 4.9 报告重试与 4.10 删除会话两个待确认接口。

> 2026-11-01 修订：认证由 APP_TOKEN 静态令牌改为账号密码会话令牌（开源产品定位）——第 1 节鉴权约定更新，新增 4.0 认证接口，第 5 节补充密码安全约束。方案全文见 `.agentdocs/plans/2026-11-01/用户认证-账号密码改造方案.md`。

## 1 通用约定

- Base URL：`http://localhost:8080`
- 鉴权：`/api` 接口需请求头 `Authorization: Bearer {会话令牌}`，令牌由 4.0 认证接口发放（2026-11-01 修订：APP_TOKEN 静态令牌退役）；register 与 login 为令牌发放入口不校验，其余接口缺失或无效返回 401
- 数据格式：请求与响应均为 `application/json`；仅 chat 接口响应为 `text/event-stream`
- 统一响应体：`{"code": 0, "message": "ok", "data": ...}`，code 非 0 表示业务错误
- 错误码：401 未认证、404 会话不存在、409 状态冲突或用户名已占用、413 内容超限、429 登录失败次数过多、500 内部错误
- 前后端分离：前端为独立 React 加 Vite 工程（仓库 frontend 目录），本文档即前后端对接契约，后端不渲染任何页面
- 跨域接入（2026-10-31 补充）：开发期前端经 Vite dev server 的 proxy 将全部 /api 请求与 SSE 流代理到 8080，浏览器视角同源；后端不开启 CORS，避免鉴权头跨域暴露。初版容器化部署采用同域反代，同样无跨域（需用户确认）

## 2 会话状态机

CREATED → IN_PROGRESS → ENDED → REPORTED

- start 将 CREATED 变为 IN_PROGRESS；chat 仅 IN_PROGRESS 可用；end 将 IN_PROGRESS 变为 ENDED；报告生成完成后为 REPORTED

## 3 数据结构

```text
SessionConfig      direction 方向(JAVA_BACKEND) difficulty(EASY MEDIUM HARD) questionCount
InterviewSummary   sessionId status direction difficulty createTime
ReportScore        overall technicalDepth expression followUpHandling  各 0-10 分
ReportItem         question userAnswerSummary comment score  逐题回顾
InterviewReport    sessionId scores items suggestions tokenTotal durationMs
ChatMessage        role content tokens time
```

## 4 接口明细

### 4.0 认证接口（2026-11-01 修订新增）

三个接口均不做会话前置校验（register 与 login 是令牌发放入口，logout 自带令牌）。

**POST /api/auth/register** 注册即登录

入参：`{"username": "2-20 位字符", "password": "至少 8 位"}`

出参：`data: { "token": "会话令牌", "username": "demo" }`

错误：用户名已占用 HTTP 409（code 40901）；参数不合法 HTTP 400（code 40001）

**POST /api/auth/login** 登录

入参与出参同上。错误：用户名或密码错误 HTTP 401（code 40101）；同用户名连续失败 5 次锁定 1 分钟，期间返回 HTTP 429（code 42901）

**POST /api/auth/logout** 登出

需携带 Bearer 会话令牌。删除当前会话，令牌即刻失效。出参 `data: null`。

**会话令牌约定**：32 字节随机 hex 不透明串；Redis 键 `auth:session:{token}`，值为 userId 与创建时间；TTL 24 小时，每次通过校验的请求滑动续期。前端存 localStorage（键沿用 `APP_TOKEN`，另存 `APP_USERNAME` 供侧栏展示）。

### 4.1 新建面试会话（实现于步骤 7）

POST /api/interviews

入参（JSON 或 multipart）：

```json
{
  "resumeText": "简历纯文本，与 resumeFile 二选一",
  "jdText": "目标岗位描述，不超过 20KB",
  "config": { "direction": "JAVA_BACKEND", "difficulty": "MEDIUM", "questionCount": 10 }
}
```

multipart 变体：resumeFile 为 PDF，上限 5MB。

出参：`data: { "sessionId": "...", "resumeDigest": "解析出的技能与项目要点摘要" }`

### 4.2 开始面试（实现于步骤 7）

POST /api/interviews/{id}/start

前置：会话状态 CREATED，否则 409。出参：`data: { "firstQuestion": "...", "focusPoints": ["考察点"] }`。状态变为 IN_PROGRESS。

### 4.3 发送回答，核心接口（实现于步骤 7）

POST /api/interviews/{id}/chat

入参：`{"content": "回答文本，上限 8KB", "images": []}`，images 为图片引用列表（P1 启用，P0 传空或不传）。状态非 IN_PROGRESS 返回 409。

响应为 SSE 流，事件序列：

```text
event: message   data: {"delta": "面试官回复增量文本"}
event: action    data: {"type": "QUESTION 或 FOLLOW_UP 或 NEXT"}
event: metrics   data: {"inputTokens": 0, "outputTokens": 0, "latencyMs": 0}
event: done      data: {"round": 3}
event: error     data: {"message": "错误描述"}
```

error 事件（2026-10-31 补充，对齐前端架构方案 4.2）：本轮生成失败时发出 error 并终结本流，不再发 done；前端气泡尾部红条提示并允许重发同一回答。

前端注意：使用 fetch 流式读取响应，不要使用 EventSource——EventSource 无法携带 Authorization 请求头，鉴权会失败。本接口与普通接口一样走 Bearer 鉴权拦截器。

断线与恢复（2026-10-31 补充，对齐前端架构方案 4.3）：用户点停止或连接意外中断时，后端不取消本轮生成，写完即止，已生成部分照常写入消息存储；前端通过会话详情接口每 3 秒轮询恢复该轮结果。不做 SSE 续传（Last-Event-ID 式恢复）。

### 4.4 结束面试（实现于步骤 7）

POST /api/interviews/{id}/end

前置：会话状态 IN_PROGRESS，否则返回 409。幂等要求：重复调用不会再次触发复盘生成，避免重复消耗报告 token。出参：`data: { "reportStatus": "GENERATING" }`。触发异步复盘生成，不阻塞。状态变为 ENDED。

### 4.5 获取复盘报告（结构定义于步骤 6，接口实现于步骤 7）

GET /api/interviews/{id}/report

- 生成中：`{"code": 202, "message": "generating", "data": {"reportStatus": "GENERATING"}}`
- 完成：`data` 为 InterviewReport 完整结构

### 4.6 历史会话列表（实现于步骤 7）

GET /api/interviews?page=1&size=10

出参：`data: { "total": 0, "list": [InterviewSummary] }`

### 4.7 会话详情（实现于步骤 7）

GET /api/interviews/{id}

出参：`data: { "session": InterviewSummary, "messages": [ChatMessage] }`

### 4.8 监控指标（实现于步骤 8）

GET /actuator/prometheus

不走 Bearer 鉴权，仅本机暴露，暴露 QPS、P95 延迟、Token 消耗、召回命中率指标。

### 4.9 报告重试（待确认，依赖后端架构方案第 11 节事项 1 与 2）

POST /api/interviews/{id}/report/retry

前置：报告状态 FAILED。作用：将报告任务置回 GENERATING 重新入队，会话状态回到 ENDED。出参：`data: { "reportStatus": "GENERATING" }`。后端架构方案第 6 节已要求"失败可手动重试"，本接口为其落地形态，待报告任务化方案确认后定形。

### 4.10 删除会话（P1 建议，待确认）

DELETE /api/interviews/{id}

历史列表的删除入口，同时清理该会话消息与报告数据。P0 不实现，列入 P1 候选。

## 5 安全相关约束（实现时必须满足，来源技术方案第 4 节）

- 全部入参做 Bean Validation：消息 8KB、JD 20KB、简历文件 5MB、图片单张 5MB（P1 启用）
- 鉴权拦截器统一前置校验，未认证请求不得进入业务逻辑；校验通过注入 userId，全部面试数据按 userId 过滤（2026-11-01 修订）
- 密码只存 BCrypt 散列，日志与任何出参不得出现明文密码（2026-11-01 修订）
- 登录接口限速：同用户名连续失败 5 次锁 1 分钟（2026-11-01 修订）
- 日志不落简历全文与密钥，疑似密钥字符串自动打码
