---
title: "Release Notes"
description: "Per-version change records for AgentScope Java"
---

This page tracks per-version changes for AgentScope Java 2.0. For the overall migration guide from 1.x, see the [V1 Migration Guide](../change-log.md).

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
