---
title: "Release Notes"
description: "Per-version change records for AgentScope Java"
---

This page tracks per-version changes for AgentScope Java 2.0. For the overall migration guide from 1.x, see the [V1 Migration Guide](../change-log.md).

---

## 2.0.1

> Released: 2026-08-05

AgentScope Java 2.0.1 is the first maintenance release after 2.0.0 GA. It expands the model-provider ecosystem, hardens Harness subagent / HITL / permission behavior, and fixes a set of production-critical issues.

**Quick links:** [Quickstart](../quickstart.md) | [V1 Migration Guide](../change-log.md) | [Going to Production](going-to-production.md)

### Added

**Core / Agent**

- Middleware execution ordering via `MiddlewareBase.order()` (higher values wrap outer); `ReActAgent.Builder.build()` stably sorts descending after all registrations ([#2532](https://github.com/agentscope-ai/agentscope-java/pull/2532), [#2449](https://github.com/agentscope-ai/agentscope-java/issues/2449))
- Session context clear API on `ReActAgent` / `HarnessAgent` to clear model-visible conversation context without creating a new session ([#2499](https://github.com/agentscope-ai/agentscope-java/pull/2499), [#2496](https://github.com/agentscope-ai/agentscope-java/issues/2496))
- Expose `ReActAgent` state-cache cleanup APIs for long-lived instances ([#2572](https://github.com/agentscope-ai/agentscope-java/pull/2572))
- Emit `UserConfirmResultEvent` when resuming permission HITL, correlatable with the prior `RequireUserConfirmEvent` via `replyId` ([#2511](https://github.com/agentscope-ai/agentscope-java/pull/2511))
- Anthropic: support configuring `disable_parallel_tool_use` ([#2257](https://github.com/agentscope-ai/agentscope-java/pull/2257))

**Model Providers**

- Add OpenAI-compatible extension package as a shared base for third-party compatible vendors ([#2208](https://github.com/agentscope-ai/agentscope-java/pull/2208))
- Add DeepSeek as a first-class model provider (`deepseek:<model>`, `DEEPSEEK_API_KEY`) ([#2307](https://github.com/agentscope-ai/agentscope-java/pull/2307), [#2211](https://github.com/agentscope-ai/agentscope-java/issues/2211))
- Add GLM (Zhipu AI) provider and dedicated formatters ([#2316](https://github.com/agentscope-ai/agentscope-java/pull/2316))
- Add Kimi (Moonshot AI) provider and dedicated formatters ([#2320](https://github.com/agentscope-ai/agentscope-java/pull/2320), [#2213](https://github.com/agentscope-ai/agentscope-java/issues/2213))
- Add MiniMax OpenAI-compatible provider ([#2299](https://github.com/agentscope-ai/agentscope-java/pull/2299))

**Harness / Tools**

- Remote subagent event streaming and HITL resume ([#2559](https://github.com/agentscope-ai/agentscope-java/pull/2559))
- Wait for async tool results by `taskId` ([#2529](https://github.com/agentscope-ai/agentscope-java/pull/2529))
- Default workspace via `AGENTSCOPE_WORKSPACE` env var for image packaging ([#2310](https://github.com/agentscope-ai/agentscope-java/pull/2310))

**AG-UI**

- Upgrade AG-UI module event mechanism ([#2306](https://github.com/agentscope-ai/agentscope-java/pull/2306), [#2202](https://github.com/agentscope-ai/agentscope-java/issues/2202))
- Introduce typed `MessageContent` / `InputContent` for multimodal AG-UI messages ([#2518](https://github.com/agentscope-ai/agentscope-java/pull/2518), [#551](https://github.com/agentscope-ai/agentscope-java/issues/551))

**Spring Boot Starters**

- Add Ollama Spring Boot Starter ([#2176](https://github.com/agentscope-ai/agentscope-java/pull/2176), [#2172](https://github.com/agentscope-ai/agentscope-java/issues/2172))

### Refactored

- Change Toolkit default execution mode to parallel and improve related docs ([#2558](https://github.com/agentscope-ai/agentscope-java/pull/2558), follow-up of [#2529](https://github.com/agentscope-ai/agentscope-java/pull/2529))
- Abstract session metadata storage to decouple builders from concrete store implementations ([#2258](https://github.com/agentscope-ai/agentscope-java/pull/2258), [#2068](https://github.com/agentscope-ai/agentscope-java/issues/2068))
- Rebase Kubernetes sandbox store on [agent-sandbox](https://github.com/kubernetes-sigs/agent-sandbox) CRDs / controllers, with the cluster owning sandbox lifecycle and warm pools ([#2308](https://github.com/agentscope-ai/agentscope-java/pull/2308))

### Fixed

**Core / Agent**

- Prevent pending recovery from consuming HITL approvals ([#2109](https://github.com/agentscope-ai/agentscope-java/pull/2109), [#2534](https://github.com/agentscope-ai/agentscope-java/issues/2534))
- Apply transformed `onModelCall` text deltas to the final message so native structured-output parsing does not see stale text ([#2469](https://github.com/agentscope-ai/agentscope-java/pull/2469), [#2385](https://github.com/agentscope-ai/agentscope-java/issues/2385))
- Repair null streaming tool args from complete raw JSON ([#2451](https://github.com/agentscope-ai/agentscope-java/pull/2451), [#768](https://github.com/agentscope-ai/agentscope-java/issues/768))
- Unbind state-saver on `ReActAgent.close()` to prevent graceful-shutdown registry growth / OOM ([#2322](https://github.com/agentscope-ai/agentscope-java/pull/2322), [#2321](https://github.com/agentscope-ai/agentscope-java/issues/2321))
- Unbind `ShutdownStateSaver` on `ReActAgent.close()` to fix a memory leak ([#2384](https://github.com/agentscope-ai/agentscope-java/pull/2384))
- Mark user interrupts with interrupted reason ([#2260](https://github.com/agentscope-ai/agentscope-java/pull/2260))
- Handle malformed Unicode when writing agent state files (`UnmappableCharacterException`) ([#2255](https://github.com/agentscope-ai/agentscope-java/pull/2255), [#2204](https://github.com/agentscope-ai/agentscope-java/issues/2204))
- Forward reasoning middleware events (e.g. `InboxMiddleware` `HintBlockEvent`) to `streamEvents()` ([#2179](https://github.com/agentscope-ai/agentscope-java/pull/2179), [#2160](https://github.com/agentscope-ai/agentscope-java/issues/2160))
- Mark `ToolResultBlock.error` as a structured error ([#2174](https://github.com/agentscope-ai/agentscope-java/pull/2174), [#2157](https://github.com/agentscope-ai/agentscope-java/issues/2157), [#2111](https://github.com/agentscope-ai/agentscope-java/issues/2111))

**Model Providers**

- DashScope: route `qwen3.8-max` to the multimodal endpoint ([#2553](https://github.com/agentscope-ai/agentscope-java/pull/2553))
- DashScope: preserve SSE error response body so callers can read `request_id` ([#2278](https://github.com/agentscope-ai/agentscope-java/pull/2278), [#2197](https://github.com/agentscope-ai/agentscope-java/issues/2197))
- OpenAI: wrap streaming branch in `Flux.defer` so retries re-issue HTTP requests ([#2079](https://github.com/agentscope-ai/agentscope-java/pull/2079))
- OpenAI: terminate stream on `[DONE]` sentinel ([#2104](https://github.com/agentscope-ai/agentscope-java/pull/2104))
- OpenAI: drop non-chunk summary event messages to avoid content duplication ([#2367](https://github.com/agentscope-ai/agentscope-java/pull/2367))
- OpenAI: sanitize `name` field in `OpenAIMessageConverter` ([#2346](https://github.com/agentscope-ai/agentscope-java/pull/2346))
- OpenAI AutoConfiguration: make api-key optional ([#2175](https://github.com/agentscope-ai/agentscope-java/pull/2175))
- DeepSeek formatter: preserve `system` role ([#2189](https://github.com/agentscope-ai/agentscope-java/pull/2189), [#2168](https://github.com/agentscope-ai/agentscope-java/issues/2168))
- Ollama: honor `stream` flag in `OllamaChatModel` ([#2415](https://github.com/agentscope-ai/agentscope-java/pull/2415))
- Anthropic: map `ToolChoice.None` to disable tools (previously incorrectly forced tool use) ([#2232](https://github.com/agentscope-ai/agentscope-java/pull/2232), [#2221](https://github.com/agentscope-ai/agentscope-java/issues/2221))
- Model provider optimizations and compatibility tweaks ([#2474](https://github.com/agentscope-ai/agentscope-java/pull/2474))

**Harness / Tools / Sandbox**

- Stamp `taskId` on remote subagent forwarded events ([#2575](https://github.com/agentscope-ai/agentscope-java/pull/2575))
- Gate memory prompt guidance on disable flags ([#2565](https://github.com/agentscope-ai/agentscope-java/pull/2565))
- Emit subagent end before parent completion to avoid dropped events ([#2544](https://github.com/agentscope-ai/agentscope-java/pull/2544))
- Close subagent event stream when the parent is cancelled ([#2481](https://github.com/agentscope-ai/agentscope-java/pull/2481), [#2480](https://github.com/agentscope-ai/agentscope-java/issues/2480))
- Enforce parent DENY rules for spawned subagents ([#2477](https://github.com/agentscope-ai/agentscope-java/pull/2477))
- Preserve `RuntimeContext` during skill promotion ([#2465](https://github.com/agentscope-ai/agentscope-java/pull/2465))
- Enforce Plan Mode for subagents ([#2377](https://github.com/agentscope-ai/agentscope-java/pull/2377))
- Reject workspace path traversal (e.g. `../`) ([#2358](https://github.com/agentscope-ai/agentscope-java/pull/2358))
- Support Windows local shell execution (working-directory commands and charset decoding) ([#2304](https://github.com/agentscope-ai/agentscope-java/pull/2304), [#2268](https://github.com/agentscope-ai/agentscope-java/issues/2268))
- Isolate static subagent registries by runtime context to prevent multi-tenant crosstalk ([#2371](https://github.com/agentscope-ai/agentscope-java/pull/2371), [#2328](https://github.com/agentscope-ai/agentscope-java/issues/2328))
- Retain prior summaries in chained compaction to preserve user intent ([#2360](https://github.com/agentscope-ai/agentscope-java/pull/2360))
- Preserve skill isolation and tool result history ([#2319](https://github.com/agentscope-ai/agentscope-java/pull/2319))
- `RemoteFilesystem` recursive glob matches files at the search root ([#2343](https://github.com/agentscope-ai/agentscope-java/pull/2343))
- Mark optional FilesystemTool params as `required=false` ([#2227](https://github.com/agentscope-ai/agentscope-java/pull/2227))
- Optimize shell-execute `working_directory` parameter and tool usage hints ([#2107](https://github.com/agentscope-ai/agentscope-java/pull/2107))
- Declared subagents inherit parent `modelExecutionConfig` / `toolExecutionConfig` ([#2252](https://github.com/agentscope-ai/agentscope-java/pull/2252))
- Correct `sessionId` parameter description ([#2195](https://github.com/agentscope-ai/agentscope-java/pull/2195))

**Storage / Transport**

- PostgreSQL BaseStore schema support ([#2273](https://github.com/agentscope-ai/agentscope-java/pull/2273), [#2192](https://github.com/agentscope-ai/agentscope-java/issues/2192))
- Fix PostgreSQL upsert SQL syntax error ([#2167](https://github.com/agentscope-ai/agentscope-java/pull/2167), [#2166](https://github.com/agentscope-ai/agentscope-java/issues/2166))
- Fix `JdkHttpTransport` SSE stream being cut by absolute timeouts ([#1322](https://github.com/agentscope-ai/agentscope-java/pull/1322), [#1302](https://github.com/agentscope-ai/agentscope-java/issues/1302))

**Spring Boot / Examples**

- Fix Spring Boot starter package name ([#2264](https://github.com/agentscope-ai/agentscope-java/pull/2264))
- Use raw DashScope model names in examples (drop invalid `dashscope:` prefix) ([#2318](https://github.com/agentscope-ai/agentscope-java/pull/2318))
- Correct DashScope model name in `RuntimeContextExample` ([#2228](https://github.com/agentscope-ai/agentscope-java/pull/2228), [#2229](https://github.com/agentscope-ai/agentscope-java/issues/2229))
- Correct skill example resource path ([#2250](https://github.com/agentscope-ai/agentscope-java/pull/2250))
- Improve docs and examples ([#2508](https://github.com/agentscope-ai/agentscope-java/pull/2508))

### Documentation

- Add Agent Evolution to the Java 2.0 feature list in README ([#2494](https://github.com/agentscope-ai/agentscope-java/pull/2494))
- Clarify that the all-in-one dependency includes model providers ([#2425](https://github.com/agentscope-ai/agentscope-java/pull/2425), [#840](https://github.com/agentscope-ai/agentscope-java/issues/840))
- Fix documentation link redirects ([#2203](https://github.com/agentscope-ai/agentscope-java/pull/2203), [#2198](https://github.com/agentscope-ai/agentscope-java/issues/2198))
- Generate version-scoped `llms.txt` artifacts (`/v1`, `/v2`) ([#2188](https://github.com/agentscope-ai/agentscope-java/pull/2188), [#2185](https://github.com/agentscope-ai/agentscope-java/issues/2185))
- Document model builder customizers ([#2092](https://github.com/agentscope-ai/agentscope-java/pull/2092))
- Update model documentation ([#2100](https://github.com/agentscope-ai/agentscope-java/pull/2100))
- Correct README doc links and release notes URLs ([#2099](https://github.com/agentscope-ai/agentscope-java/pull/2099))
- Update AG-UI documentation ([#2274](https://github.com/agentscope-ai/agentscope-java/pull/2274))

---

## 2.0.0 (GA)

> Released: 2026-07-10

AgentScope Java 2.0.0 is now Generally Available. This is the first production-ready release of the 2.0 line, marking a milestone in AgentScope Java's evolution from "transparent development" to "system engineering."

**Quick links:** [Quickstart](../quickstart.md) | [V1 Migration Guide](../change-log.md) | [Going to Production](going-to-production.md)

### 2.0 Core Design Overview

AgentScope Java 2.0 is a systematic upgrade centered on one goal: **enabling agents to reliably complete tasks**. Here is an overview of its core design:

**Dual-Layer Agent Architecture**

- **ReActAgent**: A stateless reasoning core providing the "reason → tool call → respond" ReAct loop. In 2.0, agent instances are fully stateless — all per-call mutable state is propagated via Reactor Context, allowing a single instance to safely serve multiple `(userId, sessionId)` combinations concurrently
- **HarnessAgent**: Extends ReActAgent through Middleware and Toolkit channels, adding workspace, memory, sandbox, subagents, skills, and plan mode as engineering infrastructure — the core reasoning loop is preserved, only augmented

**Message & Event Stream**

A unified ContentBlock message model (TextBlock / DataBlock / ToolUseBlock / ToolResultBlock / HintBlock, etc.) paired with `streamEvents()` emitting 28 typed AgentEvent types, making agent execution observable, interactive, and interruptible. Front-end UIs can follow text deltas, tool calls, user confirmations, and other lifecycle events in real time

**Permission System**

A new PermissionEngine establishes a three-state decision mechanism for tool calls: allow / require user approval / deny. Decisions are based on static rules, tool type, and input content analysis. Sensitive operations automatically enter a HITL approval flow

**Middleware Extension Mechanism**

A five-stage onion + pipeline hybrid model (`onAgent` / `onReasoning` / `onActing` / `onModelCall` / `onSystemPrompt`), providing flexible extension points for logging, tracing, security checks, business policies, and context injection while keeping the core framework stable

**Context Engineering**

Structured compaction preserves task objectives, current state, key findings, and next steps. Oversized tool results are automatically offloaded to disk with only placeholders in the context. File tools enforce a "read before edit" policy with built-in caching to reduce redundant IO

**Workspace Abstraction**

Decouples "what the agent does" from "where it executes." Local filesystem, Docker, Kubernetes, and E2B cloud sandbox backends are unified behind a single interface. A built-in warm-up pool supports parallel RL rollout scenarios

**Model Fault Tolerance**

A unified Credential + ModelRegistry abstraction covering Qwen / OpenAI / Anthropic / Gemini / DeepSeek / Ollama. Configurable max retries and fallback model — automatic failover when the primary model is unavailable

**Enterprise Distributed Deployment**

One-line `DistributedBackend` configuration (Redis / OSS / MySQL / PostgreSQL / COS). `AgentStateStore` auto-partitions by `(userId, sessionId)`. Cross-replica session recovery, sandbox state snapshots, and subagent cross-replica routing

**Protocol Interoperability**

Built-in A2A (Agent-to-Agent) and MCP (Model Context Protocol) support, plus AG-UI protocol adaptation, covering standardized inter-agent communication and front-end rendering needs

**Multi-Agent Orchestration**

Declarative subagent specs (YAML / Markdown), runtime `agent_spawn` / `agent_send` with synchronous blocking and background delegation modes. Subagent event streams can be forwarded to the parent's `streamEvents()` in real time

**Skill System**

Four-layer skill composition (Classpath / FileSystem / Nacos / Marketplace) + SkillFilter fine-grained filtering + self-learning closed loop (propose → curate → promote)

---

### Changes Since RC5

The following are incremental changes between 2.0.0-RC5 (2026-07-07) and the GA release.

#### Added

- Fire `AllToolsDeniedEvent` hook when HITL denies all tool calls, enabling application-level handling of full-denial scenarios ([#2083](https://github.com/agentscope-ai/agentscope-java/pull/2083))
- Add guardrails for `wait_async_results` to prevent repeated long blocking waits ([#2093](https://github.com/agentscope-ai/agentscope-java/pull/2093))
- Add `PostgresDistributedStore` for PostgreSQL-backed distributed HarnessAgent state ([#2054](https://github.com/agentscope-ai/agentscope-java/pull/2054))
- Add builder customizers for OpenAI, DashScope, and Anthropic models in Spring Boot starters ([#2045](https://github.com/agentscope-ai/agentscope-java/pull/2045))

#### Fixed

**Core / Agent**

- Make `seedSystemMsg` reactive to avoid `block()` on NIO threads ([#2086](https://github.com/agentscope-ai/agentscope-java/pull/2086))
- Include ASKING ToolUseBlocks in PERMISSION_ASKING result message ([#2082](https://github.com/agentscope-ai/agentscope-java/pull/2082))
- Activate SkillToolGroup via `activateOnSkill` field ([#2057](https://github.com/agentscope-ai/agentscope-java/pull/2057))
- Save agent state on user interrupt to prevent session loss ([#1970](https://github.com/agentscope-ai/agentscope-java/pull/1970))

**Model Providers**

- Anthropic: split parallel tool calls into alternating messages to comply with API requirements ([#2090](https://github.com/agentscope-ai/agentscope-java/pull/2090))
- OpenAI: make `nativeStructuredOutput` configurable ([#2069](https://github.com/agentscope-ai/agentscope-java/pull/2069))

**Harness / Tools / Sandbox**

- External tool execution now correctly produces a suspended result ([#2071](https://github.com/agentscope-ai/agentscope-java/pull/2071))
- Allow SkillLoadTool in Plan Mode by promoting `isReadOnly` to the AgentTool interface ([#2067](https://github.com/agentscope-ai/agentscope-java/pull/2067))
- Interrupt orphan subagents when AgentSpawnTool parent subscription cancels ([#2064](https://github.com/agentscope-ai/agentscope-java/pull/2064))
- Remove unnecessary ReActAgent type restriction in MemoryFlushMiddleware ([#2078](https://github.com/agentscope-ai/agentscope-java/pull/2078))
- Resolve leading `/` paths relative to workspace in ROOTED mode ([#2049](https://github.com/agentscope-ai/agentscope-java/pull/2049))
- Pre-stage marketplace skills before workspace projection ([#2059](https://github.com/agentscope-ai/agentscope-java/pull/2059))
- Treat null exit code as success in Kubernetes `hydrateWithArchive` ([#1915](https://github.com/agentscope-ai/agentscope-java/pull/1915))
- Use updated WorkspaceSpec when resuming from persisted state ([#1928](https://github.com/agentscope-ai/agentscope-java/pull/1928))
- Support nested JSON and banner prefix in AgentRun MCP response ([#1930](https://github.com/agentscope-ai/agentscope-java/pull/1930))
- Use resolved workingDir for Docker workspaceRoot ([#2033](https://github.com/agentscope-ai/agentscope-java/pull/2033))

**Channel**

- Include PeerKind in OutboundAddress to fix group message routing ([#2060](https://github.com/agentscope-ai/agentscope-java/pull/2060))

**A2A**

- Merge streaming text chunks to avoid fragmentation ([#2058](https://github.com/agentscope-ai/agentscope-java/pull/2058))

---

## 2.0.0-RC5

> Released: 2026-07-07

### Breaking Changes

- **Model provider modularization** — OpenAI, Gemini, Anthropic, DashScope, and Ollama model providers have been moved from `agentscope-core` into independent `agentscope-extensions-model-*` extension modules. Applications must add the corresponding extension dependency ([#1890](https://github.com/agentscope-ai/agentscope-java/pull/1890), [#1916](https://github.com/agentscope-ai/agentscope-java/pull/1916), [#1947](https://github.com/agentscope-ai/agentscope-java/pull/1947), [#1972](https://github.com/agentscope-ai/agentscope-java/pull/1972))

### Added

- Unified `DataBlock` support in all provider message converters (OpenAI, DashScope, Gemini, Anthropic), covering single-agent, multi-agent, and tool-result paths ([#1933](https://github.com/agentscope-ai/agentscope-java/pull/1933))
- Native structured output handling with tools — models that support structured output can enforce JSON schema constraints alongside tool calls ([#1904](https://github.com/agentscope-ai/agentscope-java/pull/1904))
- Native structured output support for DashScope models ([#1935](https://github.com/agentscope-ai/agentscope-java/pull/1935))
- `httpRequestCustomizer` support in `McpClientBuilder` for dynamic token injection (e.g. OAuth refresh) ([#1992](https://github.com/agentscope-ai/agentscope-java/pull/1992))
- Align `AguiEvent` with the AG-UI protocol spec — add missing event types ([#1862](https://github.com/agentscope-ai/agentscope-java/pull/1862))
- Optional skill allowlist filter for subagents ([#1873](https://github.com/agentscope-ai/agentscope-java/pull/1873))
- `knownSkillNames` support in `NacosSkillRepository` ([#1853](https://github.com/agentscope-ai/agentscope-java/pull/1853))
- `CosAgentStateStore`, `CosBaseStore` and `CosDistributedStore` for Tencent Cloud COS-backed state persistence ([#1857](https://github.com/agentscope-ai/agentscope-java/pull/1857))
- Expose cached prompt tokens in `ChatUsage` ([#1868](https://github.com/agentscope-ai/agentscope-java/pull/1868))

### Fixed

**Core / Agent**

- Persist agent state on user interrupt recovery ([#2008](https://github.com/agentscope-ai/agentscope-java/pull/2008))
- Wire fallback model into `ReActAgent` ([#1851](https://github.com/agentscope-ai/agentscope-java/pull/1851))
- Fix `ReActAgent` stream event block end ordering ([#1829](https://github.com/agentscope-ai/agentscope-java/pull/1829))
- Update `ToolResultBlock` state before adding to agent context ([#1886](https://github.com/agentscope-ai/agentscope-java/pull/1886))
- Reuse classpath skill JAR file systems to avoid resource leaks ([#1981](https://github.com/agentscope-ai/agentscope-java/pull/1981))
- Resolve `serializeOnKey` gate leak in `Flux.create` callbacks ([#1796](https://github.com/agentscope-ai/agentscope-java/pull/1796))

**Model Providers**

- Map `thinkingBudget` to OpenAI-compatible API request ([#2028](https://github.com/agentscope-ai/agentscope-java/pull/2028))
- Fix Anthropic stream thinking event handling ([#1943](https://github.com/agentscope-ai/agentscope-java/pull/1943))
- Preserve `executionConfig` in `OllamaOptions` `fromOptions`/`toBuilder` ([#2011](https://github.com/agentscope-ai/agentscope-java/pull/2011))
- Degrade forced tool choice in DashScope thinking mode ([#1882](https://github.com/agentscope-ai/agentscope-java/pull/1882))

**Harness / Sandbox**

- Restore remote snapshot state deserialization — re-inject `RemoteSnapshotClient` after Jackson round-trip ([#2013](https://github.com/agentscope-ai/agentscope-java/pull/2013))
- Fix THROTTLED memory save mode losing state when recreating instances per request ([#1788](https://github.com/agentscope-ai/agentscope-java/pull/1788))
- Propagate `userId` through wakeup dispatch ([#2001](https://github.com/agentscope-ai/agentscope-java/pull/2001))
- Run message bus heartbeat on `boundedElastic` instead of `parallel` scheduler ([#1974](https://github.com/agentscope-ai/agentscope-java/pull/1974))
- Avoid duplicating `GracefulShutdownMiddleware` in `fromAgent` ([#1952](https://github.com/agentscope-ai/agentscope-java/pull/1952))
- Escape spaces in skill paths returned by `ShellPathPolicy` ([#2031](https://github.com/agentscope-ai/agentscope-java/pull/2031))
- Fallback to simple key-value extraction when YAML parsing fails ([#2027](https://github.com/agentscope-ai/agentscope-java/pull/2027))
- Report sandbox file sizes in `ls` ([#1838](https://github.com/agentscope-ai/agentscope-java/pull/1838))
- Normalize Windows `list_files` paths ([#1892](https://github.com/agentscope-ai/agentscope-java/pull/1892))
- Normalize `\r\n` to `\n` for file content in `LocalFilesystem.edit()` ([#2020](https://github.com/agentscope-ai/agentscope-java/pull/2020))
- Treat `"."` as root equivalent in `CompositeFilesystem` ([#1830](https://github.com/agentscope-ai/agentscope-java/pull/1830))
- Validate `working_directory` to prevent namespace escape ([#1834](https://github.com/agentscope-ai/agentscope-java/pull/1834))
- Fall back to `LocalFilesystemSpec` when no distributed `AgentStateStore` is configured ([#1841](https://github.com/agentscope-ai/agentscope-java/pull/1841))
- Fix WebSocket race in Kubernetes `hydrateWithArchive` causing `exit=null` ([#1903](https://github.com/agentscope-ai/agentscope-java/pull/1903))
- Tolerate wrapped sandbox base64 downloads ([#1866](https://github.com/agentscope-ai/agentscope-java/pull/1866))
- Remove `AgentRun` sandbox API version prefix ([#1891](https://github.com/agentscope-ai/agentscope-java/pull/1891))
- Add connect JSON codec support for E2B sandbox ([#1844](https://github.com/agentscope-ai/agentscope-java/pull/1844))

**Tracing / Observability**

- Fix orphan spans in `OtelTracingMiddleware` by reading parent OTel Context from Reactor `ContextView` ([#1940](https://github.com/agentscope-ai/agentscope-java/pull/1940))
- Fix child spans not seeing correct parent spans in `OtelTracingMiddleware` ([#1909](https://github.com/agentscope-ai/agentscope-java/pull/1909))
- Propagate Reactor context to chunk event hooks ([#1923](https://github.com/agentscope-ai/agentscope-java/pull/1923))

**Subagent**

- Propagate parent `RuntimeContext` to child agents ([#1833](https://github.com/agentscope-ai/agentscope-java/pull/1833))
- Propagate parent middleware to subagents ([#1843](https://github.com/agentscope-ai/agentscope-java/pull/1843))

**A2A**

- Handle streaming backpressure ([#1734](https://github.com/agentscope-ai/agentscope-java/pull/1734))
- Preserve AgentScope message roles across A2A conversion ([#1995](https://github.com/agentscope-ai/agentscope-java/pull/1995))

**AG-UI**

- Propagate run input and frontend tools ([#1895](https://github.com/agentscope-ai/agentscope-java/pull/1895))

**Other**

- Wrap middleware `doFlush` in `Mono.defer` to prevent premature evaluation ([#1880](https://github.com/agentscope-ai/agentscope-java/pull/1880))
- Nacos auto-configurations should be opt-in (`matchIfMissing=false`) and fix A2A server-addr override ([#1709](https://github.com/agentscope-ai/agentscope-java/pull/1709))
- Add `ObjectMapper` bean for `MarketContributionService` in DataAgent ([#1993](https://github.com/agentscope-ai/agentscope-java/pull/1993))

### Documentation

- Clarify stream event `blockId` semantics ([#2016](https://github.com/agentscope-ai/agentscope-java/pull/2016))
- Improve model provider documentation ([#1986](https://github.com/agentscope-ai/agentscope-java/pull/1986))
- Remove invalid `ChatResponse.isLast` references ([#1921](https://github.com/agentscope-ai/agentscope-java/pull/1921))
- Fix multi-replica Redis example — declare jedis dependency and add `stateStore` ([#1869](https://github.com/agentscope-ai/agentscope-java/pull/1869))
- Fix `MemoryCompactionExample` to show memory files and fire compaction ([#1978](https://github.com/agentscope-ai/agentscope-java/pull/1978))

---

## 2.0.0-RC4

> Released: 2026-06-18

### Added

- Agent harness now supports async tool execution and notifications, including message bus, async tool registry, and scheduled wakeup dispatching ([#1802](https://github.com/agentscope-ai/agentscope-java/pull/1802))
- String/Message convenience overloads for agent calls; all formatters now support `HintBlock` ([#1802](https://github.com/agentscope-ai/agentscope-java/pull/1802))
- Persistent spawn registry in tool context state enables subagent cross-replica routing and session recovery ([#1817](https://github.com/agentscope-ai/agentscope-java/pull/1817))
- `DynamicSkillMiddleware` implements `ToolkitAware` to receive the resolved toolkit dynamically ([#1828](https://github.com/agentscope-ai/agentscope-java/pull/1828))
- Kubernetes sandbox now supports injecting environment variables into pods ([#1789](https://github.com/agentscope-ai/agentscope-java/pull/1789))

### Fixed

- Fixed SIGKILL race condition in Kubernetes file uploads by using two-phase archive strategy ([#1826](https://github.com/agentscope-ai/agentscope-java/pull/1826))
- Fixed resource leak where timed-out sub-agents were not interrupted on retry ([#1784](https://github.com/agentscope-ai/agentscope-java/pull/1784))
- Fixed typed attributes being lost when copying `RuntimeContext` ([#1813](https://github.com/agentscope-ai/agentscope-java/pull/1813))
- Fixed `JdbcStore` table initialization failure under MySQL utf8mb4 charset ([#1781](https://github.com/agentscope-ai/agentscope-java/pull/1781))
- Made session JSONL offload idempotent to prevent duplicate writes ([#1774](https://github.com/agentscope-ai/agentscope-java/pull/1774))
- Fixed OpenTelemetry context propagation in `TelemetryTracer` ([#1799](https://github.com/agentscope-ai/agentscope-java/pull/1799))
- Fixed NPE in `OllamaChatModel` when options are null during tool choice retrieval ([#1803](https://github.com/agentscope-ai/agentscope-java/pull/1803))
- Added missing Jackson annotations to `LocalSandboxSnapshot` for proper serialization ([#1825](https://github.com/agentscope-ai/agentscope-java/pull/1825))
- Fixed sandbox glob not supporting `**/` recursive patterns ([#1684](https://github.com/agentscope-ai/agentscope-java/pull/1684))
- Fixed `SkillFilter` matching using composite ID instead of skill name ([#1771](https://github.com/agentscope-ai/agentscope-java/pull/1771))
- Allow custom default vision model in `MultiModalTool` ([#1701](https://github.com/agentscope-ai/agentscope-java/pull/1701))

### Documentation

- Fixed incorrect hook signatures in middleware docs ([#1835](https://github.com/agentscope-ai/agentscope-java/pull/1835))
- Fixed references to non-existent `.sandboxContext()` in doc examples ([#1792](https://github.com/agentscope-ai/agentscope-java/pull/1792))
- Fixed `getToolName()` → `getToolCallName()` in v2 docs ([#1760](https://github.com/agentscope-ai/agentscope-java/pull/1760))
- Added AI context menu to documentation site

---

## 2.0.0-RC3

> Released: 2026-06-11

### Added

- **`AgentResultEvent`** — new event type emitted when an agent finishes processing, immediately before `AgentEndEvent`, carrying the final `Msg` result. Consumers of `streamEvents()` can obtain the result directly from the event stream without separately subscribing to the `Mono<Msg>` return value
- **`CustomEvent`** — generic extensible event for middleware to push application-level notifications (state changes, team updates, etc.) to front-end subscribers without adding per-use-case `AgentEventType` entries. Built-in well-known names: `state_updated`, `team_updated`
- **`HintBlockEvent`** — one-shot hint block event for delivering complete content such as team messages, background tool results, and user interruptions, as opposed to streamed text/thinking blocks
- **`WorkspacePathNormalizer`** — file path normalization utility that converts absolute paths to workspace-relative form. Registers prefixes based on the active filesystem mode (local / sandbox) to prevent cross-mode prefix collisions
- **`toolCallName` on tool events** — `ToolCallDeltaEvent`, `ToolCallEndEvent`, `ToolResultDataDeltaEvent`, `ToolResultEndEvent`, and `ToolResultTextDeltaEvent` now carry a `toolCallName` field, so consumers no longer need to cache the name mapping from the start event

### Changed

- **Unified `call()` / `streamEvents()` core** — introduced an internal `buildAgentStream` method as the shared implementation for both `call()` and `streamEvents()`, ensuring the `onAgent` middleware chain fires consistently on all invocation paths. `call()` now extracts the result from `AgentResultEvent` in the event stream; the legacy standalone `agentImpl` logic has been removed
- **Session state always reloaded from store in distributed deployments** — when an `AgentStateStore` is configured, `activateSlotForContext` now reloads the agent state and permission engine from the store at the start of every call, preventing stale local cache reads when the same sessionId drifts across machines
- **`ToolResultEvictionMiddleware` timing fix** — moved from `onActing` (where state had not yet been written, making eviction a no-op) to `onReasoning`, ensuring tool results are persisted before eviction runs
- **Simplified `LocalFilesystem` path resolution** — refactored path resolution logic to reduce redundant code

### Fixed

- Fixed `RuntimeContext` not setting `userId` in tests, causing inaccurate user isolation

---

## 2.0.0-RC2

> Released: 2026-06-09

### Added

- **`projectWritable` mode** (`LocalFilesystemSpec`) — when enabled, the agent's file writes are routed by path: workspace metadata (`MEMORY.md`, `agents/`, `skills/`, etc.) goes to workspace; everything else (code, configs) lands in the project directory. Designed for code-generation agents. See [Filesystem · Project-writable mode](../harness/filesystem.md#project-writable-mode-projectwritable)
- **Runtime permission mode switching** — new `HarnessAgent.setPermissionMode()` / `getPermissionMode()` for dynamically adjusting the permission mode per session at runtime
- **Subagent event stream forwarding** — `streamEvents()` now forwards child agent intermediate events (`TextBlockDelta`, `ToolCallStart`, etc.) in real time, each carrying a `source` path identifying the originating agent
- **`AgentEvent.source` field** — all `AgentEvent` instances now carry a `source` field to distinguish main agent events (`source = null`) from sub agent events (`source = "main/researcher"` path format) within the same event stream, enabling consumer-side demuxing without extra state
- **Custom prompt and model for Compaction / Memory** — `CompactionConfig` and `MemoryConfig` gain `.model()` and `.prompt()` builder methods, allowing a dedicated lightweight model and custom prompt for context compaction and memory extraction instead of the agent's primary model
- **Qwen 3.7 model support** — `ModelRegistry` now resolves `dashscope:qwen3.7-plus` and other Qwen 3.7 series models
- **Direct subagent messaging** — `agent_send` lets callers send messages directly to a declared subagent and receive its response without going through the parent agent's reasoning loop
- **Channel module** — new `agentscope-extensions-channel` module family for IM platform integration (DingTalk, Feishu/Lark, WeCom, GitHub, GitLab), with a built-in ChatUI for an out-of-the-box conversational interface
- **`DistributedBackend` unified interface** — new `DistributedBackend` abstraction that consolidates all distributed storage components (`AgentStateStore`, `BaseStore`, `SandboxSnapshotSpec`) into a single configuration point. Built-in implementations include `RedisDistributedBackend`, `OssDistributedBackend`, and `MysqlDistributedBackend`. One call to `HarnessAgent.builder().distributedBackend(backend)` wires up the entire distributed backend — no more separate stateStore, baseStore, and snapshotSpec configuration

### Changed

- **Agent fully stateless** — `ReActAgent` no longer holds any mutable per-session state; all mutable state is encapsulated in an internal `CallExecution` and propagated via Reactor Context. A single agent instance can safely serve multiple `(userId, sessionId)` combinations concurrently
- **Session interface replaced by `AgentStateStore`** — removed `SessionManager`, `StatePersistence`, and related legacy interfaces; unified on `AgentStateStore` (built-in: `InMemoryAgentStateStore`, `JsonFileAgentStateStore`, `RedisAgentStateStore`, `MysqlAgentStateStore`), auto-partitioned by `(userId, sessionId)`
- **`BaseStore` interface package renamed** — `BaseStore` and related interfaces moved to a new package; code using the old import path needs updating
- **Extension module coordinates consolidated** — several extension Maven coordinates have been reorganized by capability. For example, `agentscope-extensions-session-redis` is now `agentscope-extensions-redis` (bundling `RedisAgentStateStore`, `RedisStore`, `RedisSnapshotSpec`, etc.). Update `<artifactId>` in your pom if you were using the old coordinates
- **Sandbox implementations extracted from harness core** — Docker, Kubernetes, E2B, Daytona, AgentRun sandbox backends have been moved out of `agentscope-harness` into standalone extension modules (`agentscope-extensions-sandbox-*`). The harness core retains only the abstract interfaces (`SandboxFilesystemSpec`, etc.) and no longer transitively pulls in any concrete sandbox dependency. Add the corresponding extension explicitly if you need sandbox support, e.g. `agentscope-extensions-sandbox-docker` for Docker
- **Plan Mode improvements** — improved plan file persistence and recovery, smoother `plan_enter` / `plan_write` / `plan_exit` tool-chain interaction, more robust HITL approval flow
- **Skill self-evolution enhancements** — refined the propose (`ProposeSkillTool`) → curate (`SkillCurator`) → promote (`SkillPromoter`) closed loop, improved skill matching accuracy and cross-session reuse
- `DashScopeHttpClient` request timeout and retry policy adjustments
- `ModelRegistry` model resolution logic improvements
- `AgentState` serialization format updates

### Fixed

- Fixed `PermissionContextState` losing state during cross-session restoration
- Fixed `agentscope-all` missing 4 sandbox extension modules (`sandbox-kubernetes`, `sandbox-agentrun`, `sandbox-daytona`, `sandbox-e2b`)

---

## 2.0.0-RC1

> Released: 2025-05-28

First 2.0 Release Candidate. Contains the full architectural upgrade from 1.x:

- Harness engineering (workspace, memory, skills, subagents, Plan Mode, context compaction)
- Enterprise-grade distributed deployment (multi-tenant isolation, sandbox execution, permission system, session recovery)
- Core framework redesign (event stream, message model, Middleware, HITL)

For the complete 1.x → 2.0 change list, see the [V1 Migration Guide](../change-log.md).
