# AI 模拟面试平台（mockInterview）

多智能体模拟面试应用：输入简历与 Java 后端岗位描述，AI 面试官多轮提问与追问，面试结束输出结构化评分与复盘报告。

## 当前状态

设计完成（2026-10-29）。前后端分离：后端在本仓库 main 分支 `backend/` 目录开发，前端 React 加 Vite 工程由前端开发在独立分支维护。

- 步骤 0 工程初始化与基础设施编排：已完成，五容器 healthy，应用健康检查 UP
- 步骤 1 DashScope 接入与最小面试官智能体：代码就绪，待填入密钥验证
- 运行时策略（2026-10-31）：开发期本地优先——MySQL 与 Redis 用本机服务，Milvus 从步骤 2 起按需容器启动；完整 Docker Compose 部署留待初版（步骤 8、9）

## 目录结构

```text
mockInterview/
├── backend/      后端 Spring Boot 工程（main 分支）
├── frontend/     前端 React 加 Vite 工程（独立分支维护）
└── .agentdocs/   方案与变更文档
```

## 文档

- 需求文档：`.agentdocs/plans/2026-10-29/AI模拟面试平台-需求文档.md`
- 技术方案：`.agentdocs/plans/2026-10-29/AI模拟面试平台-技术方案.md`
- 接口文档：`.agentdocs/plans/2026-10-29/AI模拟面试平台-接口文档.md`
- 简历要点对照：`.agentdocs/plans/2026-10-29/AI模拟面试平台-简历要点对照.md`
- 前端页面方案：`.agentdocs/plans/2026-10-29/AI模拟面试平台-前端页面-优化方案.md`（定稿设计稿 `frontend/design-mockups/`，预览 `node frontend/serve.js`）
- 开发指令步骤 0-1：`.agentdocs/plans/2026-10-29/AI模拟面试平台-开发指令-步骤01.md`

## 技术栈（后端）

Java 21、Spring Boot 3.4、AgentScope-Java 2.0、MySQL 8（容器映射 3307）、Redis 7（容器映射 6380）、Milvus 2.6、Resilience4j、Prometheus 加 Grafana、Docker Compose、通义千问（DashScope）

## 快速开始（后端）

```bash
cd backend
cp .env.example .env      # 填入 DASHSCOPE_API_KEY 与 APP_TOKEN
mvn compile exec:java     # 命令行最小面试官：只依赖模型密钥，无需任何中间件

# 开发模式：中间件用本机服务（MySQL 3306 / Redis 6379）
# 本机 MySQL 需先建 interview 库，凭据经环境变量传入
MYSQL_USERNAME=root MYSQL_PASSWORD=本机密码 mvn spring-boot:run

# 会话持久化（步骤 5 起）：建表 + 全链路自检（需 MySQL 与 Redis 在运行）
mvn compile exec:java -Dexec.mainClass=com.liuzihe.interview.session.SessionCliRunner -Dexec.args="init-db"
mvn compile exec:java -Dexec.mainClass=com.liuzihe.interview.session.SessionCliRunner -Dexec.args="selfcheck"

# 初版部署（步骤 8、9）：一键启动全部容器
docker compose up -d
# 开发期仅需 Milvus 时（步骤 2 起）：docker compose up -d milvus
```
