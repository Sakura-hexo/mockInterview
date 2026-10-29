# AI 模拟面试平台 接口文档

作者:Sakura | 创建时间:2026-10-29

本文档定义 REST 接口边界与数据结构，供开发步骤 7 实现全量接口、步骤 6 提前定义报告结构。SSE 流式协议在步骤 7 落地时如需微调，回架构师确认后修订本文档。

## 1 通用约定

- Base URL：`http://localhost:8080`
- 鉴权：全部 `/api` 接口需请求头 `Authorization: Bearer {APP_TOKEN}`，APP_TOKEN 存于 `.env`；缺失或错误返回 401
- 数据格式：请求与响应均为 `application/json`；仅 chat 接口响应为 `text/event-stream`
- 统一响应体：`{"code": 0, "message": "ok", "data": ...}`，code 非 0 表示业务错误
- 错误码：401 未认证、404 会话不存在、409 状态冲突、413 内容超限、500 内部错误
- 前后端分离：前端为独立 React 加 Vite 工程（仓库 frontend 目录），本文档即前后端对接契约，后端不渲染任何页面

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
```

前端注意：使用 fetch 流式读取响应，不要使用 EventSource——EventSource 无法携带 Authorization 请求头，鉴权会失败。本接口与普通接口一样走 Bearer 鉴权拦截器。

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

## 5 安全相关约束（实现时必须满足，来源技术方案第 4 节）

- 全部入参做 Bean Validation：消息 8KB、JD 20KB、简历文件 5MB、图片单张 5MB（P1 启用）
- 鉴权拦截器统一前置校验，未认证请求不得进入业务逻辑
- 日志不落简历全文与密钥，疑似密钥字符串自动打码
