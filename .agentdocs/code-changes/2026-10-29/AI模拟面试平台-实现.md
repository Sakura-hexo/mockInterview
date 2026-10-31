# AI 模拟面试平台 实现（步骤 0 与步骤 1）

作者:开发执行 AI（ZCode）| 创建时间:2026-10-29 23:40

对应计划：同目录上级 `plans/2026-10-29/AI模拟面试平台-开发指令-步骤01.md`。

## 1 本批次做了什么

步骤 0 工程初始化：

- `git init`（main 分支），远程配置 `github.com/Sakura-hexo/mockInterview`
- 后端代码按用户要求全部位于 `backend/` 目录（前后端分离，前端在独立分支）
- `backend/pom.xml`：Spring Boot 3.4.13（查证为 3.4.x 最新补丁）加 Java 21
- `backend/src` 下 `com.sakura.interview` 七个包占位（agent tool rag session api infra obs）
- `.gitignore`（含 .env、application-local.yml、target、IDE、.agentscope）、`.env.example`
- `backend/docker-compose.yml`：mysql 8.4、redis 7-alpine、milvus standalone 及其依赖 etcd、minio，全部带健康检查与数据卷
- `application.yml`：数据源与 Redis 全环境变量占位，密钥零明文；Actuator 暴露 health

步骤 1 DashScope 接入与最小面试官智能体：

- AgentScope-Java 2.0.3：`io.agentscope:agentscope-harness` 加 `agentscope-extensions-model-dashscope`，坐标经官方站 java.agentscope.io 查证，未凭记忆猜
- `infra/EnvKeys`：先读进程环境变量，其次读 `.env`（当前目录或上级），密钥零明文
- `infra/DashScopeModels`：DashScopeChatModel 显式 apiKey 构建，默认 qwen-plus
- `agent/InterviewerPrompts`：系统提示词锁定面试官角色，对抗性输入一律按面试回答处理（技术方案 4.3）
- `agent/InterviewerAgent`：ReActAgent 加 InMemoryAgentStateStore，按 userId 与 sessionId 累积多轮记忆
- `agent/InterviewerCliRunner`：命令行入口，每轮打印输入输出 token 与耗时
- pom 增加 exec-maven-plugin，运行方式 `mvn compile exec:java`

## 2 遇到的问题与解决方式

| 问题 | 解决方式 |
|---|---|
| quay.io 直连 TLS 超时，加速器不含 minio 旧标签 | 改用本机已缓存镜像：milvus v2.6.1、mysql 8.4、redis 7-alpine、etcd v3.5.18；minio 用本机 2025-09 版并重打确切版本标签 |
| 本机服务占端口：MySQL 服务占 3306，memurai 占 6379 | 容器映射改为 3307 与 6380，应用默认连接随之调整，保证连的是容器而非本机服务 |
| 8080 被用户进程占用 | 先以 `--server.port=8090` 临时验证通过；端口释放后已补做 8080 正式验证，同样 UP |
| 验证期间 Docker Desktop 意外退出，容器全部停止 | 重启 Docker Desktop 后 `docker compose up -d` 恢复；为全部服务增加 `restart: unless-stopped`，此后 Docker 重启容器自动拉起 |
| 远程仓库已有 Initial commit | 本地两个提交 rebase 到其上（README 冲突保留本项目完整版），线性历史后推送 |
| etcd 3.5.18 的 etcdctl 不认连字符别名 | 健康检查命令改为 `etcdctl endpoint health`（空格形式） |
| AgentScope API 签名不确定 | javap 检视本地仓库 jar 确认：`ReActAgent.Builder.model(Model)`、`call(String, RuntimeContext)`、`Msg.getUsage()`、`InMemoryAgentStateStore` |
| 多轮记忆机制 | 官方 v2 文档确认 ReActAgent 需 stateStore 按 userId 加 sessionId 持久化，已配置 |

另：按用户要求完成前后端分离文档修订（需求文档 7.1、技术方案第 1/7/8/10 节、接口文档第 1 节、开发指令），并新增 README 目录结构说明。

## 3 验证记录

- 容器：`docker compose ps` 五个容器全部 healthy（interview-mysql、interview-redis、milvus-etcd、milvus-minio、milvus-standalone）
- 编译：`mvn compile` BUILD SUCCESS
- 健康检查（正式）：`curl http://localhost:8080/actuator/health` 返回 `{"status":"UP","components":{"db":UP,"redis":UP 7.4.11}}`；此前 8080 被占时曾以 8090 临时验证，结果一致
- 密钥安全：`git grep -r "sk-"` 仅命中文档中提及命令的文字，无真实密钥；`git ls-files` 确认 `.env` 未被追踪
- 无密钥错误路径：CLI 直接运行给出中文提示「未找到 DASHSCOPE_API_KEY：请设置环境变量，或复制 backend/.env.example 为 backend/.env 并填入真实密钥」
- 命令行 3 轮对话：（待用户创建 backend/.env 填入密钥后执行并补录）

## 4 提交记录

已推送 GitHub（main 分支，rebase 于远程 Initial commit 之上）：

- `docs: 方案文档定稿与前后端分离修订`
- `step0: 工程初始化与基础设施编排`
- `step1: dashscope接入与最小面试官agent`（待 3 轮对话验证通过后提交推送）
