---
title: Harness Architecture
description: What HarnessAgent is, how to choose an invocation style, combine capabilities and persist conversation state
zh_link: /v2/zh/docs/harness/architecture
---

`HarnessAgent` combines reasoning, tools and context management with workspace, long-term memory, skills, subagents and sandboxes. Start with a working conversation in [Quickstart](/v2/en/docs/quickstart), then add capabilities for your application.

`ReActAgent` provides the reasoning loop, tools, permissions and conversation context APIs. Harness composes these capabilities with additional defaults and integrations. Both support `call` and `streamEvents`.

Configure a shared `HarnessAgent.Builder` at startup, build a fresh instance for each direct request, and close it after execution. Stable identity and the same log backend preserve the conversation. See [Instance lifecycle](/v2/en/docs/building-blocks/agent#instance-lifecycle) for code and the shared-instance option. A session manager retains the Agent for background `AgentSession` execution.

## Core working principle

An execution loads the conversation state, builds model input, calls tools as needed and saves its results. Workspace, memory and compaction capabilities take effect during these steps. Applications customize behavior through the builder, tools and middleware.

## Choose an invocation style

- **Get a reply or invoke an Agent within a workflow**: use `call(input, ctx)`.
- **Display live text and tool progress within the current request**: use `streamEvents(input, ctx)`.
- **Keep tasks running in the background, with queueing, guidance and interrupted-task continuation**: use `AgentSession` from `agent.session(ctx)`.

Direct calls support ongoing conversations and save state through the default Session Log and checkpoints. AgentSession also handles durable task acceptance, scheduling and pending-action association, so applications do not need to manage execution subscriptions for these operations. Both styles use the same Agent's model, tools and middleware configuration.

See [Agent](/v2/en/docs/building-blocks/agent) for direct calls and [Session operations, events and recovery](/v2/en/docs/harness/session-log) for background session workflows. Applications with an existing workflow scheduler can continue using direct calls; hosted HTTP integrations use the [Service Agent API](/v2/en/service/session-event-log).

## Core components

Configure capabilities and use session operations as your application needs them.

| Capability | What it solves | Configuration or operation | Detail |
|---|---|---|---|
| Workspace-driven persona | Persona, knowledge, subagent specs, skills, MCP allowlist all live as files | `.workspace(path)` | [Workspace](/v2/en/docs/harness/workspace) |
| State and execution history | Full facts, checkpoints, cross-request/node recovery | EVENT_LOG by default; override sessionLogStore | [Session logs](/v2/en/docs/harness/session-log) |
| Session task management | Background work, durable queueing, guidance and recovery | `agent.session(ctx)` | [Session operations](/v2/en/docs/harness/session-log) |
| Two-layer long-term memory | Facts in long conversations sediment into `MEMORY.md` | on by default; `.memory(...)` customizes prompts / trigger policy | [Memory](/v2/en/docs/harness/memory) |
| Conversation compaction | History bounded; force-retry on real overflow | `.compaction(...)` | [Context management](/v2/en/docs/harness/context) |
| Large tool-result offloading | >80K-char results moved to disk + placeholder | `.toolResultEviction(...)` | [Context management](/v2/en/docs/harness/context) |
| Subagent orchestration | Delegate to children, sync or background, with auto push-back | `.subagent(...)` or drop spec in `workspace/subagents/` | [Subagent](/v2/en/docs/harness/subagent) |
| Pluggable filesystem | Local + shell / shared store / sandbox without code changes | `.filesystem(...)` | [Filesystem](/v2/en/docs/harness/filesystem) |
| Sandbox isolation | Files and commands isolated; cross-call recovery; multi-replica | `.filesystem(new DockerFilesystemSpec()...)` | [Sandbox](/v2/en/docs/harness/sandbox) |
| Plan Mode | Read-only think-first phase with HITL exit | `.enablePlanMode()` | [Plan Mode](/v2/en/docs/harness/plan-mode) |
| Skill composition | Skills from Git / Nacos / MySQL / classpath / workspace | `.skillRepository(...)` | [Skill](/v2/en/docs/harness/skill) |
| MCP integration & tool allowlist | Declarative MCP servers + allow/deny per tool | `workspace/tools.json` | [Workspace](/v2/en/docs/harness/workspace) |
| Channel routing | Session management, per-session concurrency, multi-agent routing, streaming events | `agent.channel(...)` / `GatewayBootstrap` | [Channel](/v2/en/docs/harness/channel) |

## How state flows

Three layers exist; the framework moves data between them automatically.

- **In-call state** — `AgentState` (conversation context, permission rules, Plan Mode state, tool state) plus `RuntimeContext` (`sessionId`, `userId`, sandbox handle, extras).
- **Cross-call state** — Default recovery uses native checkpoints and applicable facts; full history remains and JSONL is exported only on demand. LEGACY use AgentStateStore; files, tasks and sandbox metadata retain their own backends.
- **Long-term memory** — accumulated across sessions: `memory/YYYY-MM-DD.md` is append-only, periodically merged into `MEMORY.md` by a throttled background job; `MEMORY.md` loads once per call as reference context, not System instructions.

Three invariants worth remembering:

- Final requests are rebuilt each reasoning step, but workspace files load per call. Edits to AGENTS.md or MEMORY.md take effect on the next call without restarting.
- Compaction, memory distillation, and background maintenance are throttled; they don't run every turn.
- Core manages persistence: EVENT_LOG commits checkpoints; LEGACY save legacy state. Harness selects the default log backend.

## Adding your own middleware

To insert custom behaviour without bypassing Harness's plumbing:

- Use `.middleware(...)` — your middleware runs before all Harness built-ins.
- Read `RuntimeContext` from the agent for the current call's identity (`userId` / `sessionId`).
- For workspace I/O, go through `harnessAgent.getWorkspaceManager()` — it routes correctly under sandbox or remote-store modes. `java.nio.Files` writes to the host disk and will land in the wrong place outside local mode.

## Related pages

- [Session operations, events and recovery](/v2/en/docs/harness/session-log) — background tasks, queues, interaction and continuation
- [Recoverable chat example](/v2/en/blogs/best-practices/session-chat) — a complete application from submission to frontend reconnection
- [Workspace](/v2/en/docs/harness/workspace) — directory layout, instruction and reference sources, `tools.json`
- [Context & AgentState](/v2/en/docs/building-blocks/context) — `AgentState`, `RuntimeContext`, `AgentStateStore` persistence, multi-user isolation
- [Memory](/v2/en/docs/harness/memory) — two-layer memory
- [Context management](/v2/en/docs/harness/context) — build model input, track long-running tasks, summarize history, and offload large results
- [Filesystem](/v2/en/docs/harness/filesystem) — local + shell / shared store / sandbox
- [Sandbox](/v2/en/docs/harness/sandbox) — isolated execution, cross-call recovery, distributed
- [Subagent](/v2/en/docs/harness/subagent) — declarations, sync/background, streaming forwarding
- [Skill](/v2/en/docs/harness/skill) — four-layer composition, self-learning loop
- [Plan Mode](/v2/en/docs/harness/plan-mode) — read-only phase + HITL exit
- [Channel](/v2/en/docs/harness/channel) — session management, multi-agent routing, streaming SSE

## Final model input construction

Harness organizes System, conversation, task state and references at the final model-call boundary. Supply dynamic business information through contextSource. See [Context management](/v2/en/docs/harness/context) for configuration, defaults and limits.
