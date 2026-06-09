# 集成总览

本节汇总 AgentScope Java 与第三方系统、生态服务的集成扩展。每个扩展都是 `agentscope-extensions/` 下的独立 Maven 模块，按需引入即可。

按主题分为以下几组：

## 分布式存储（Distributed Store）

生产多副本部署所需的全链路分布式存储组件。通过 `DistributedStore` 一键配置 Agent 状态、工作区文件系统、沙箱快照与并发锁。

- [分布式存储总览](distributed/index.md) — `DistributedStore` API、能力矩阵、混合后端
- [Redis](distributed/redis.md) — `AgentStateStore` + `BaseStore` + `SandboxSnapshotSpec` + `SandboxExecutionGuard`
- [MySQL / JDBC](distributed/mysql.md) — `AgentStateStore` + `JdbcStore` + `JdbcSnapshotSpec` + `JdbcSandboxExecutionGuard`
- [阿里云 OSS](distributed/oss.md) — `AgentStateStore` + `OssBaseStore` + `OssSnapshotSpec`

## 沙箱执行环境（Sandbox）

隔离的代码执行环境，所有 `SandboxFilesystemSpec` 实现。Docker 内置在 harness 中，其余为独立扩展模块。

- Docker — 内置默认，无需额外依赖
- [Kubernetes](../docs/harness/sandbox.md) — `agentscope-extensions-sandbox-kubernetes`
- [AgentRun（阿里云）](../docs/harness/sandbox.md) — `agentscope-extensions-sandbox-agentrun`
- [Daytona](../docs/harness/sandbox.md) — `agentscope-extensions-sandbox-daytona`
- [E2B](../docs/harness/sandbox.md) — `agentscope-extensions-sandbox-e2b`

## 记忆（Memory）

跨会话持久化用户偏好与事实，所有实现都符合 `LongTermMemory` 接口。

- [Mem0](memory/mem0.md)
- [百炼记忆](memory/bailian.md)
- [ReMe](memory/reme.md)

## RAG 知识库

通过 `Knowledge` 接口接入不同的检索后端。

- [Simple（自建 embedding + 向量库）](rag/simple.md)
- [百炼知识库](rag/bailian.md)
- [Dify](rag/dify.md)
- [HayStack](rag/haystack.md)
- [RAGFlow](rag/ragflow.md)

## 技能仓库（Skill）

`AgentSkillRepository` 的多种存储实现。

- [Git 技能仓库](skill/git-repository.md)
- [MySQL 技能仓库](skill/mysql-repository.md)
- [PostgreSQL 技能仓库](skill/postgresql-repository.md)
- 也可以使用 [Nacos 技能仓库](infrastructure/nacos.md#skill-仓库)

## Channel 适配器

通过 Harness Channel 接口将 Agent 接入消息平台。

- [钉钉](channel/dingtalk.md)
- [飞书 / Lark](channel/feishu.md)
- [GitHub](channel/github.md)
- [GitLab](channel/gitlab.md)
- [企业微信](channel/wecom.md)

## 智能体协议

让 Agent 与外部世界以标准方式交互。

- [A2A（Agent-to-Agent）](protocol/a2a.md)
- [AG-UI](protocol/agui.md)
- [Agent Protocol](protocol/agent-protocol.md)

## 基础设施 / 中间件

把 Agent 接到企业基础设施。

- [Higress AI 网关](infrastructure/higress.md)
- [Nacos](infrastructure/nacos.md)
- [Scheduler（Quartz / XXL-Job）](infrastructure/scheduler.md)

## 生态扩展

运行环境、语言生态、调试与训练流水线。

- [Chat Completions Web](ecosystem/chat-completions-web.md)
- [AgentScope Studio](ecosystem/studio.md)
- [在线训练（Training）](ecosystem/training.md)

```{note}
若你正在使用 Spring Boot，绝大多数扩展都有对应的 `agentscope-spring-boot-starter-*` 一键接入版本，可减少手动装配代码。
```
