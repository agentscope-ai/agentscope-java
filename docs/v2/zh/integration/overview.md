---
title: 概览
---

本节汇总 AgentScope Java 与第三方系统、生态服务的集成扩展。每个扩展都是 `agentscope-extensions/` 下的独立 Maven 模块，按需引入即可。

按主题分为以下几组：

## 模型提供商

所有模型提供商已经迁移到独立模型扩展模块中，`agentscope-core` 只保留共享模型契约。完整模型创建方式、Spring Boot 配置、formatter、credential 和高级 registry 行为见 [模型文档](/v2/zh/docs/building-blocks/model)。

| 提供商 | Maven artifact | `ModelRegistry` id | 标准环境变量 | 文档 |
|--------|----------------|--------------------|--------------|------|
| OpenAI | `agentscope-extensions-model-openai` | `openai:<model>` | `OPENAI_API_KEY` | <a class="reference internal" href="/v2/zh/integration/model/openai">OpenAI</a> |
| DeepSeek | `agentscope-extensions-model-openai` | `deepseek:<model>` | `DEEPSEEK_API_KEY` | <a class="reference internal" href="/v2/zh/integration/model/deepseek">DeepSeek</a> |
| GLM | `agentscope-extensions-model-openai` | `glm:<model>` | `ZAI_API_KEY` / `GLM_API_KEY` / `ZHIPUAI_API_KEY` | <a class="reference internal" href="/v2/zh/integration/model/glm">GLM</a> |
| Kimi | `agentscope-extensions-model-openai` | `kimi:<model>` | `MOONSHOT_API_KEY` / `KIMI_API_KEY` | <a class="reference internal" href="/v2/zh/integration/model/kimi">Kimi</a> |
| MiniMax | `agentscope-extensions-model-openai` | `minimax:<model>` | `MINIMAX_API_KEY` | <a class="reference internal" href="/v2/zh/integration/model/minimax">MiniMax</a> |
| DashScope | `agentscope-extensions-model-dashscope` | `dashscope:<model>` / `qwen*` | `DASHSCOPE_API_KEY` | <a class="reference internal" href="/v2/zh/integration/model/dashscope">DashScope</a> |
| Gemini | `agentscope-extensions-model-gemini` | `gemini:<model>` | `GEMINI_API_KEY` | <a class="reference internal" href="/v2/zh/integration/model/gemini">Gemini</a> |
| Anthropic | `agentscope-extensions-model-anthropic` | `anthropic:<model>` | `ANTHROPIC_API_KEY` | <a class="reference internal" href="/v2/zh/integration/model/anthropic">Anthropic</a> |
| Ollama | `agentscope-extensions-model-ollama` | `ollama:<model>` | `OLLAMA_BASE_URL`（可选） | <a class="reference internal" href="/v2/zh/integration/model/ollama">Ollama</a> |


<Note>

`agentscope-extensions-model-e2e-tests` 是仓库测试模块，不是面向用户的模型集成依赖。

</Note>


## 分布式存储（Distributed Store）

生产多副本部署所需的全链路分布式存储组件。通过 `DistributedStore` 一键配置 Agent 状态、工作区文件系统、沙箱快照与并发锁。

- [分布式存储总览](/v2/zh/integration/distributed/index) — `DistributedStore` API、能力矩阵、混合后端
- [Redis](/v2/zh/integration/distributed/redis) — `AgentStateStore` + `BaseStore` + `SandboxSnapshotSpec` + `SandboxExecutionGuard`
- [MySQL / JDBC](/v2/zh/integration/distributed/mysql) — `AgentStateStore` + `JdbcStore` + `JdbcSnapshotSpec` + `JdbcSandboxExecutionGuard`
- [阿里云 OSS](/v2/zh/integration/distributed/oss) — `AgentStateStore` + `OssBaseStore` + `OssSnapshotSpec`

## 沙箱执行环境（Sandbox）

隔离的代码执行环境，所有 `SandboxFilesystemSpec` 实现。Docker 内置在 harness 中，其余为独立扩展模块。

- Docker — 内置默认，无需额外依赖
- [Kubernetes](/v2/zh/docs/harness/sandbox) — `agentscope-extensions-sandbox-kubernetes`
- [AgentRun（阿里云）](/v2/zh/docs/harness/sandbox) — `agentscope-extensions-sandbox-agentrun`
- [Daytona](/v2/zh/docs/harness/sandbox) — `agentscope-extensions-sandbox-daytona`
- [E2B](/v2/zh/docs/harness/sandbox) — `agentscope-extensions-sandbox-e2b`

## 记忆（Memory）

跨会话持久化用户偏好与事实，所有实现都符合 `LongTermMemory` 接口。

- [Mem0](/v2/zh/integration/memory/mem0)
- [百炼记忆](/v2/zh/integration/memory/bailian)
- [ReMe](/v2/zh/integration/memory/reme)

## RAG 知识库

通过 `Knowledge` 接口接入不同的检索后端。

- [Simple（自建 embedding + 向量库）](/v2/zh/integration/rag/simple)
- [百炼知识库](/v2/zh/integration/rag/bailian)
- [Dify](/v2/zh/integration/rag/dify)
- [HayStack](/v2/zh/integration/rag/haystack)
- [RAGFlow](/v2/zh/integration/rag/ragflow)

## 技能仓库（Skill）

`AgentSkillRepository` 的多种存储实现。

- [Git 技能仓库](/v2/zh/integration/skill/git-repository)
- [MySQL 技能仓库](/v2/zh/integration/skill/mysql-repository)
- [PostgreSQL 技能仓库](/v2/zh/integration/skill/postgresql-repository)
- 也可以使用 [Nacos 技能仓库](/v2/zh/integration/infrastructure/nacos#skill-仓库)

## Channel 适配器

通过 Harness Channel 接口将 Agent 接入消息平台。

- [钉钉](/v2/zh/integration/channel/dingtalk)
- [飞书 / Lark](/v2/zh/integration/channel/feishu)
- [GitHub](/v2/zh/integration/channel/github)
- [GitLab](/v2/zh/integration/channel/gitlab)
- [企业微信](/v2/zh/integration/channel/wecom)

## 智能体协议

让 Agent 与外部世界以标准方式交互。

- [A2A（Agent-to-Agent）](/v2/zh/integration/protocol/a2a)
- [AG-UI](/v2/zh/integration/protocol/agui)
- [Agent Protocol](/v2/zh/integration/protocol/agent-protocol)

## 基础设施 / 中间件

把 Agent 接到企业基础设施。

- [Higress AI 网关](/v2/zh/integration/infrastructure/higress)
- [Nacos](/v2/zh/integration/infrastructure/nacos)
- [Scheduler（Quartz / XXL-Job）](/v2/zh/integration/infrastructure/scheduler)

## 生态扩展

运行环境、语言生态、调试与训练流水线。

- [Chat Completions Web](/v2/zh/integration/ecosystem/chat-completions-web)
- [AgentScope Studio](/v2/zh/integration/ecosystem/studio)
- [在线训练（Training）](/v2/zh/integration/ecosystem/training)


<Note>

若你正在使用 Spring Boot，绝大多数扩展都有对应的 `agentscope-spring-boot-starter-*` 一键接入版本，可减少手动装配代码。

</Note>
