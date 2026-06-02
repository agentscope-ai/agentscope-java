# Integration Overview

This section collects the AgentScope Java extensions that connect to third-party systems and ecosystem services. Each extension is an independent Maven module under `agentscope-extensions/` — pull in only what you need.

The extensions are grouped by topic:

## Memory

Persist user preferences and facts across sessions. All implementations satisfy the `LongTermMemory` interface.

- [Mem0](memory/mem0.md)
- [Bailian Memory](memory/bailian.md)
- [ReMe](memory/reme.md)

## Session

Persist runtime state (Memory, Workspace, Plan, ...) into a database or cache.

- [MySQL Session](session/mysql.md)
- [Redis Session](session/redis.md)

## RAG Knowledge Base

Plug different retrieval backends behind the unified `Knowledge` interface.

- [Simple (DIY embedding + vector store)](rag/simple.md)
- [Bailian Knowledge](rag/bailian.md)
- [Dify](rag/dify.md)
- [HayStack](rag/haystack.md)
- [RAGFlow](rag/ragflow.md)

## Skill Repository

Multiple storage implementations of `AgentSkillRepository`.

- [Git Skill Repository](skill/git-repository.md)
- [MySQL Skill Repository](skill/mysql-repository.md)
- See also [Nacos Skill Repository](infrastructure/nacos.md#skill-repository)

## Agent Protocols

Standardized ways for the Agent to talk to the outside world.

- [A2A (Agent-to-Agent)](protocol/a2a.md)
- [AG-UI](protocol/agui.md)
- [Agent Protocol](protocol/agent-protocol.md)

## Infrastructure / Middleware

Plug Agents into your enterprise infrastructure.

- [Higress AI Gateway](infrastructure/higress.md)
- [Nacos](infrastructure/nacos.md)
- [RocketMQ](infrastructure/rocketmq.md)
- [Scheduler (Quartz / XXL-Job)](infrastructure/scheduler.md)

## Ecosystem

Runtime, language, debugging, and training extensions.

- [Chat Completions Web](ecosystem/chat-completions-web.md)
- [Kotlin Extensions](ecosystem/kotlin.md)
- [AgentScope Studio](ecosystem/studio.md)
- [Online Training](ecosystem/training.md)

```{note}
For Spring Boot users, most of the above extensions ship a matching `agentscope-spring-boot-starter-*` for one-line integration that removes the manual wiring.
```
