# Integration Overview

This section collects the AgentScope Java extensions that connect to third-party systems and ecosystem services. Each extension is an independent Maven module under `agentscope-extensions/` — pull in only what you need.

The extensions are grouped by topic:

## Model Providers

All model providers have moved to independent model extension modules, while `agentscope-core` keeps only the shared model contracts. See [Model](../docs/building-blocks/model.md) for the full creation paths, Spring Boot setup, formatters, credentials, and advanced registry behavior.

| Provider | Maven artifact | `ModelRegistry` id | Standard environment variable | Docs |
|----------|----------------|--------------------|-------------------------------|------|
| OpenAI | `agentscope-extensions-model-openai` | `openai:<model>` | `OPENAI_API_KEY` | <a class="reference internal" href="model/openai.html">OpenAI</a> |
| DashScope | `agentscope-extensions-model-dashscope` | `dashscope:<model>` / `qwen*` | `DASHSCOPE_API_KEY` | <a class="reference internal" href="model/dashscope.html">DashScope</a> |
| Gemini | `agentscope-extensions-model-gemini` | `gemini:<model>` | `GEMINI_API_KEY` | <a class="reference internal" href="model/gemini.html">Gemini</a> |
| Anthropic | `agentscope-extensions-model-anthropic` | `anthropic:<model>` | `ANTHROPIC_API_KEY` | <a class="reference internal" href="model/anthropic.html">Anthropic</a> |
| Ollama | `agentscope-extensions-model-ollama` | `ollama:<model>` | `OLLAMA_BASE_URL` optional | <a class="reference internal" href="model/ollama.html">Ollama</a> |

```{note}
`agentscope-extensions-model-e2e-tests` is a repository test module, not a user-facing model integration dependency.
```

## Distributed Storage (Distributed Store)

Full-stack distributed storage components for multi-replica production deployments. Configure agent state, workspace filesystem, sandbox snapshots, and concurrency locks with a single `DistributedStore`.

- [Distributed Storage Overview](distributed/index.md) — `DistributedStore` API, capability matrix, mixed stores
- [Redis](distributed/redis.md) — `AgentStateStore` + `BaseStore` + `SandboxSnapshotSpec` + `SandboxExecutionGuard`
- [MySQL / JDBC](distributed/mysql.md) — `AgentStateStore` + `JdbcStore` + `JdbcSnapshotSpec` + `JdbcSandboxExecutionGuard`
- [Alibaba Cloud OSS](distributed/oss.md) — `AgentStateStore` + `OssBaseStore` + `OssSnapshotSpec`

## Sandbox Execution Environments

Isolated code execution stores. Docker is built-in; the rest are standalone extension modules.

- Docker — built-in default, no extra dependency
- [Kubernetes](../docs/harness/sandbox.md) — `agentscope-extensions-sandbox-kubernetes`
- [AgentRun (Alibaba Cloud)](../docs/harness/sandbox.md) — `agentscope-extensions-sandbox-agentrun`
- [Daytona](../docs/harness/sandbox.md) — `agentscope-extensions-sandbox-daytona`
- [E2B](../docs/harness/sandbox.md) — `agentscope-extensions-sandbox-e2b`

## Memory

Persist user preferences and facts across sessions. All implementations satisfy the `LongTermMemory` interface.

- [Mem0](memory/mem0.md)
- [Bailian Memory](memory/bailian.md)
- [ReMe](memory/reme.md)

## RAG Knowledge Base

Plug different retrieval stores behind the unified `Knowledge` interface.

- [Simple (DIY embedding + vector store)](rag/simple.md)
- [Bailian Knowledge](rag/bailian.md)
- [Dify](rag/dify.md)
- [HayStack](rag/haystack.md)
- [RAGFlow](rag/ragflow.md)

## Skill Repository

Multiple storage implementations of `AgentSkillRepository`.

- [Git Skill Repository](skill/git-repository.md)
- [MySQL Skill Repository](skill/mysql-repository.md)
- [PostgreSQL Skill Repository](skill/postgresql-repository.md)
- See also [Nacos Skill Repository](infrastructure/nacos.md#skill-repository)

## Channel Adapters

Connect your Agent to messaging platforms through the Harness Channel interface.

- [DingTalk](channel/dingtalk.md)
- [Feishu / Lark](channel/feishu.md)
- [GitHub](channel/github.md)
- [GitLab](channel/gitlab.md)
- [WeCom](channel/wecom.md)

## Agent Protocols

Standardized ways for the Agent to talk to the outside world.

- [A2A (Agent-to-Agent)](protocol/a2a.md)
- [AG-UI](protocol/agui.md)
- [Agent Protocol](protocol/agent-protocol.md)

## Infrastructure / Middleware

Plug Agents into your enterprise infrastructure.

- [Higress AI Gateway](infrastructure/higress.md)
- [Nacos](infrastructure/nacos.md)
- [Scheduler (Quartz / XXL-Job)](infrastructure/scheduler.md)

## Ecosystem

Runtime, language, debugging, and training extensions.

- [Chat Completions Web](ecosystem/chat-completions-web.md)
- [AgentScope Studio](ecosystem/studio.md)
- [Online Training](ecosystem/training.md)

```{note}
For Spring Boot users, most of the above extensions ship a matching `agentscope-spring-boot-starter-*` for one-line integration that removes the manual wiring.
```
