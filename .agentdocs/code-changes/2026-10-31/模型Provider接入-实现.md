# AI 模拟面试平台 模型Provider接入 实现

作者:开发执行 AI（ZCode）| 创建时间:2026-10-31 14:10

对应计划：`plans/2026-10-31/模型Provider接入-优化方案.md`。

## 1 本批次做了什么

按方案完成三路模型 provider 接入与环境本地化，步骤 1 代码一并就绪：

- `backend/pom.xml`：新增 `agentscope-extensions-model-openai` 与 `agentscope-extensions-model-ollama` 两个依赖，版本走既有 `agentscope.version` 属性（2.0.3）
- `infra/DashScopeModels.java` 删除，新建 `infra/ChatModels.java`：统一入口 `chat()` 按 `MODEL_PROVIDER` 分派三路——不填默认 dashscope（原逻辑原样保留）、openai（智谱等 OpenAI 兼容端点）、ollama（本地免密钥）
- `infra/EnvKeys.java`：新增 `modelProvider` 与 openai、ollama 两组键的读取方法，统一经 `orDefault` helper 处理空值；openai 路缺密钥时给出中文指引异常
- `agent/InterviewerAgent.java`：`create()` 改为接受 `Model` 参数注入（模型选择上移到调用方，为步骤 4 AgentRuntime 端口化铺路）
- `agent/InterviewerCliRunner.java`：启动时打印当前模型源与模型名，验证三路切换时可直接确认走的哪条路
- `backend/.env.example`：补齐 MODEL_PROVIDER、OPENAI_API_KEY、OPENAI_BASE_URL、OPENAI_MODEL、OLLAMA_BASE_URL、OLLAMA_MODEL 六键，并补上此前遗漏的 DASHSCOPE_MODEL
- `application.yml`：数据源默认端口 3307 改 3306、Redis 默认 6380 改 6379（本地优先决策落地，连本机 MySQL 与 memurai）

## 2 遇到的问题与解决方式

| 问题 | 解决方式 |
|---|---|
| 智谱 OpenAI 兼容端点的 URL 拼接不确定 | javap 加 jar 二进制常量提取核验：openai 扩展默认 endpointPath 为 /chat/completions，故 baseUrl 填 https://open.bigmodel.cn/api/paas/v4 即正确拼出端点 |
| 统一工厂的返回类型 | javap 核验 `io.agentscope.core.model.Model` 接口存在且 `ReActAgent.Builder.model(Model)` 接受它，三路模型类均实现该接口 |
| Git Bash 无 strings 命令 | 用 `unzip -p` 管道加 `grep -aoE` 从 class 二进制提取常量替代 |
| mvn 不在 Bash PATH | 用完整路径 /d/apache-maven-3.9.13/bin/mvn 执行 |

## 3 验证记录

- 编译：`mvn -q compile` 退出码 0，BUILD SUCCESS
- Ollama 路 3 轮对话（`MODEL_PROVIDER=ollama mvn -q compile exec:java`，管道输入三问后 exit）：启动行显示「模型源 ollama / qwen2.5:7b」；三轮均正常回复，第 3 轮出现真实追问（对 HashMap 线程安全回答给出"正确但不全面"评价并追问包装机制）；每轮打印输入输出 token 与耗时（261/26/1020ms、307/36/855ms、361/77/1656ms），输入 token 随轮次递增证明 stateStore 多轮记忆生效
- 密钥安全：`git grep -r "sk-" -- backend/` 无结果；`backend/.env` 不存在（真实密钥仍未产生，智谱与百炼注册后由用户自建）
- 中转站路（智谱路最终形态）3 轮对话验证通过：用户粘贴的智谱 key 实为 OpenAI 兼容中转站 key（sk- 格式 51 字符，智谱原生 key 为带点号 49 字符），直连智谱返回 401；经 key 形状盲查与盲测 curl 确认代码链路无误、仅 key 归属不符（诊断全程不接触密钥值）。按用户提供的 OpenAI 兼容中转站接入，OPENAI_BASE_URL 指向中转站 v1 端点，经 v1/models 查询可用模型后选 qwen3.8-max（与正式口径 qwen 同家族）。3 轮正常回复，模型对答非所问输入保持面试官控场并逐次细化追问，输入 token 284、443、762 递增，多轮记忆生效
- 顺带核验中转站模型列表含 qwen3-embedding-4b 与 text-embedding-v3，记为步骤 2 RAG 的 embedding 备选项（选型仍按方案走通义）
- DashScope 路：留待百炼注册后验证，正式评测与简历口径不变

## 4 提交记录

- `docs: 后端架构修订、接口契约补充与模型provider方案`（技术方案、接口文档、后端架构方案、provider 方案、README）
- `step1: 模型provider三路接入与环境本地化`（backend 全部改动与本目录实现记录）
