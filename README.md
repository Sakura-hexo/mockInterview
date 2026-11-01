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

Java 21、Spring Boot 3.4、AgentScope-Java 2.0、MySQL 8（容器映射 3308）、Redis 7、Milvus 2.6、Resilience4j（步骤 8）、Prometheus 加 Grafana（步骤 8）、Docker Compose、通义千问（DashScope）

## 快速开始（后端）

```bash
cd backend
cp .env.example .env      # 填入模型密钥（如 DASHSCOPE_API_KEY 或 OPENAI_API_KEY）
mvn compile exec:java     # 命令行最小面试官：只依赖模型密钥，无需任何中间件

# Web 完整服务：注册登录在页面完成，无需配置令牌
# MySQL 8 与 Redis 需在运行（本机或 docker compose up -d mysql），首启自动建表与迁移
MYSQL_USERNAME=root MYSQL_PASSWORD=本机密码 mvn spring-boot:run   # 默认 8080 端口

# 角色分级：辅助调用（摘要路由解析等）走 OPENAI_MODEL_FAST 指定的快档模型，未配置回退主档
# 前端开发模式（另开终端）：vite 5173 起，/api 代理到 8080
cd frontend && npm install && npm run dev
# 生产部署：前端构建产物直接落 Spring Boot static（零拷贝，同 8080 服务）
cd frontend && npm run build

# 运维子命令
mvn compile exec:java -Dexec.mainClass=com.sakura.interview.session.SessionCliRunner -Dexec.args="selfcheck"   # 持久化全链路自检
mvn compile exec:java -Dexec.mainClass=com.sakura.interview.session.SessionCliRunner -Dexec.args="reset-data"  # 清空业务数据（账号保留）

# 开发期：只起中间件容器（MySQL 3308、Redis 6380、Milvus 19530），应用跑本机
docker compose up -d

# 监控栈（可选）：Prometheus 抓 /actuator/prometheus，Grafana 3000 预置面板（admin/admin）
docker compose --profile monitoring up -d

# 初版部署：宿主机构建镜像后起完整栈（应用容器 + 中间件 + 监控）
mvn package -DskipTests
docker compose --profile deploy --profile monitoring up -d
```
