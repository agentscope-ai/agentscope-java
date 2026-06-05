# 集成总览

本节汇总 AgentScope Java 与第三方系统、生态服务的集成扩展。每个扩展都是 `agentscope-extensions/` 下的独立 Maven 模块，按需引入即可。

按主题分为以下几组：

## 记忆（Memory）

跨会话持久化用户偏好与事实，所有实现都符合 `LongTermMemory` 接口。

- [Mem0](memory/mem0.md)
- [百炼记忆](memory/bailian.md)
- [ReMe](memory/reme.md)

## 会话（Session）

把 Memory / Workspace / Plan 等运行时状态持久化到数据库或缓存。

- [MySQL Session](session/mysql.md)
- [Redis Session](session/redis.md)

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
- 也可以使用 [Nacos 技能仓库](infrastructure/nacos.md#skill-仓库)

## 智能体协议

让 Agent 与外部世界以标准方式交互。

- [A2A（Agent-to-Agent）](protocol/a2a.md)
- [AG-UI](protocol/agui.md)
- [Agent Protocol](protocol/agent-protocol.md)

## 基础设施 / 中间件

把 Agent 接到企业基础设施。

- [Higress AI 网关](infrastructure/higress.md)
- [Nacos](infrastructure/nacos.md)
- [RocketMQ](infrastructure/rocketmq.md)
- [Scheduler（Quartz / XXL-Job）](infrastructure/scheduler.md)

## 生态扩展

运行环境、语言生态、调试与训练流水线。

- [Chat Completions Web](ecosystem/chat-completions-web.md)
- [Kotlin 扩展](ecosystem/kotlin.md)
- [AgentScope Studio](ecosystem/studio.md)
- [在线训练（Training）](ecosystem/training.md)

```{note}
若你正在使用 Spring Boot，绝大多数扩展都有对应的 `agentscope-spring-boot-starter-*` 一键接入版本，可减少手动装配代码。
```
