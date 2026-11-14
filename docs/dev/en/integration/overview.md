---
title: Overview
zh_link: /dev/zh/integration/overview
---

This section collects the AgentScope Java extensions that connect to third-party systems and ecosystem services. Each extension is an independent Maven module under `agentscope-extensions/` — pull in only what you need.

The extensions are grouped by topic:

## Model Providers

All model providers have moved to independent model extension modules, while `agentscope-core` keeps only the shared model contracts. See [Model](/dev/en/docs/building-blocks/model) for the full creation paths, Spring Boot setup, formatters, credentials, and advanced registry behavior.

| Provider | Maven artifact | `ModelRegistry` id | Standard environment variable | Docs |
|----------|----------------|--------------------|-------------------------------|------|
| OpenAI | `agentscope-extensions-model-openai` | `openai:<model>` | `OPENAI_API_KEY` | <a class="reference internal" href="/dev/en/integration/model/openai">OpenAI</a> |
| OpenAI Official | `agentscope-extensions-model-openai-official` | `openai-official:<model>` | `OPENAI_API_KEY` | <a class="reference internal" href="/dev/en/integration/model/openai-official">OpenAI Official</a> |
| DeepSeek | `agentscope-extensions-model-openai` | `deepseek:<model>` | `DEEPSEEK_API_KEY` | <a class="reference internal" href="/dev/en/integration/model/deepseek">DeepSeek</a> |
| GLM | `agentscope-extensions-model-openai` | `glm:<model>` | `ZAI_API_KEY` / `GLM_API_KEY` / `ZHIPUAI_API_KEY` | <a class="reference internal" href="/dev/en/integration/model/glm">GLM</a> |
| Kimi | `agentscope-extensions-model-openai` | `kimi:<model>` | `MOONSHOT_API_KEY` / `KIMI_API_KEY` | <a class="reference internal" href="/dev/en/integration/model/kimi">Kimi</a> |
| MiniMax | `agentscope-extensions-model-openai` | `minimax:<model>` | `MINIMAX_API_KEY` | <a class="reference internal" href="/dev/en/integration/model/minimax">MiniMax</a> |
| DashScope | `agentscope-extensions-model-dashscope` | `dashscope:<model>` / `qwen*` | `DASHSCOPE_API_KEY` | <a class="reference internal" href="/dev/en/integration/model/dashscope">DashScope</a> |
| Gemini | `agentscope-extensions-model-gemini` | `gemini:<model>` | `GEMINI_API_KEY` | <a class="reference internal" href="/dev/en/integration/model/gemini">Gemini</a> |
| Anthropic | `agentscope-extensions-model-anthropic` | `anthropic:<model>` | `ANTHROPIC_API_KEY` | <a class="reference internal" href="/dev/en/integration/model/anthropic">Anthropic</a> |
| Ollama | `agentscope-extensions-model-ollama` | `ollama:<model>` | `OLLAMA_BASE_URL` optional | <a class="reference internal" href="/dev/en/integration/model/ollama">Ollama</a> |


<Note>

`agentscope-extensions-model-e2e-tests` is a repository test module, not a user-facing model integration dependency.

</Note>


## Distributed Storage (Distributed Store)

Full-stack distributed storage components for multi-replica production deployments. Configure agent state, workspace filesystem, sandbox snapshots, and concurrency locks with a single `DistributedStore`.

- [Distributed Storage Overview](/dev/en/integration/distributed/index) — `DistributedStore` API, capability matrix, mixed stores
- [Redis](/dev/en/integration/distributed/redis) — `AgentStateStore` + `BaseStore` + `SandboxSnapshotSpec` + `SandboxExecutionGuard`
- [JDBC](/dev/en/integration/distributed/jdbc) — `AgentStateStore` + `JdbcStore` + `JdbcSnapshotSpec` + `JdbcSandboxExecutionGuard`
- [Alibaba Cloud OSS](/dev/en/integration/distributed/oss) — `AgentStateStore` + `OssBaseStore` + `OssSnapshotSpec`

## Sandbox Execution Environments

Isolated code execution stores. Docker is built-in; the rest are standalone extension modules.

- Docker — built-in default, no extra dependency
- [Kubernetes](/dev/en/docs/harness/sandbox) — `agentscope-extensions-sandbox-kubernetes`
- [AgentRun (Alibaba Cloud)](/dev/en/docs/harness/sandbox) — `agentscope-extensions-sandbox-agentrun`
- [Daytona](/dev/en/docs/harness/sandbox) — `agentscope-extensions-sandbox-daytona`
- [E2B](/dev/en/docs/harness/sandbox) — `agentscope-extensions-sandbox-e2b`

## Memory

Persist user preferences and facts across sessions. All implementations satisfy the `LongTermMemory` interface.

- [Mem0](/dev/en/integration/memory/mem0)
- [Bailian Memory](/dev/en/integration/memory/bailian)
- [ReMe](/dev/en/integration/memory/reme)

## RAG Knowledge Base

Plug different retrieval stores behind the unified `Knowledge` interface.

- [Simple (DIY embedding + vector store)](/dev/en/integration/rag/simple)
- [Bailian Knowledge](/dev/en/integration/rag/bailian)
- [Dify](/dev/en/integration/rag/dify)
- [HayStack](/dev/en/integration/rag/haystack)
- [RAGFlow](/dev/en/integration/rag/ragflow)

## Skill Repository

Multiple storage implementations of `AgentSkillRepository`.

- [Git Skill Repository](/dev/en/integration/skill/git-repository)
- [MySQL Skill Repository](/dev/en/integration/skill/mysql-repository)
- [PostgreSQL Skill Repository](/dev/en/integration/skill/postgresql-repository)
- See also [Nacos Skill Repository](/dev/en/integration/infrastructure/nacos#skill-repository)

## Channel Adapters

Connect your Agent to messaging platforms through the Harness Channel interface.

- [DingTalk](/dev/en/integration/channel/dingtalk)
- [Feishu / Lark](/dev/en/integration/channel/feishu)
- [GitHub](/dev/en/integration/channel/github)
- [GitLab](/dev/en/integration/channel/gitlab)
- [WeCom](/dev/en/integration/channel/wecom)

## Agent Protocols

Standardized ways for the Agent to talk to the outside world.

- [A2A (Agent-to-Agent)](/dev/en/integration/protocol/a2a)
- [AG-UI](/dev/en/integration/protocol/agui)
- [Agent Protocol](/dev/en/integration/protocol/agent-protocol)

## Infrastructure / Middleware

Plug Agents into your enterprise infrastructure.

- [Higress AI Gateway](/dev/en/integration/infrastructure/higress)
- [Nacos](/dev/en/integration/infrastructure/nacos)
- [Scheduler (Quartz / XXL-Job)](/dev/en/integration/infrastructure/scheduler)

## Ecosystem

Runtime, language, debugging, and training extensions.

- [Chat Completions Web](/dev/en/integration/ecosystem/chat-completions-web)
- [AgentScope Studio](/dev/en/integration/ecosystem/studio)
- [Online Training](/dev/en/integration/ecosystem/training)


<Note>

For Spring Boot users, most of the above extensions ship a matching `agentscope-spring-boot-starter-*` for one-line integration that removes the manual wiring.

</Note>
