# 压测脚本（k6）

两个脚本对应两个口径，报告中分开呈现：

| 脚本 | 口径 | 场景 |
| --- | --- | --- |
| `static.js` | 静态链路（无 LLM） | 50 VU × 20s，每轮建会话（t_analysis_cache 命中）+ 会话列表 |
| `chat.js` | 会话链路（真实 LLM） | 10 VU 各一轮全链路：注册、建会话、开始、一轮 chat SSE 到 done |

## 运行

```bash
# 前置：后端 8080 已启动，且已有一次同简历同 JD 的建会话（写入了 t_analysis_cache）
k6 run backend/loadtest/static.js
k6 run backend/loadtest/chat.js
```

静态脚本要求 RESUME/JD 与建缓存的输入逐字节一致——不一致时 create 退化为真实 LLM 调用，
并发下既烧模型额度还可能触发熔断。脚本里的文本就是缓存键的输入，改动前先想清楚。

## 踩坑记录（2026-11-02）

1. **k6 自动 cookie jar 与 SameSite=Strict**：注册响应下发的 `INTERVIEW_SESSION`
   Cookie，k6 自动 jar 从每 VU 第二轮起不再携带，表现为全量 401 风暴、零后端日志。
   两个脚本都改为手动解析 Set-Cookie 并显式携带 Cookie 头。
2. **熔断器真实生效一次**：早期脚本简历文本与缓存不一致 + 中转恰好 500，50 VU 的
   create 全部变成模型调用，llm 快档熔断器按设计打开，毫秒级拒绝保护了下游与额度。
   该轮"失败"其实是稳定性层在工作，复盘时不要误读为接口缺陷。
3. **连接池定档**：池 3 时 50 并发 create 排队（med 438ms / p95 906ms / 108 RPS），
   池 10 后 med 247ms / p95 499ms / 233 RPS，见 `MYSQL_POOL` 配置项。

## 清理

压测会写入 `load_*`、`chatload_*` 前缀用户与会话，结束后手动清理
（DELETE t_session WHERE user_id IN (压测用户)；DELETE t_user WHERE username LIKE 'load_%' OR username LIKE 'chatload_%'）。
