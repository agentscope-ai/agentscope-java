---
title: Harness Architecture
description: What HarnessAgent is, how its capabilities cooperate, and how state flows
  during a call()
zh_link: /v2/zh/docs/harness/architecture
---

`HarnessAgent` is a thin wrapper around `ReActAgent` that packages the engineering capabilities long-running agents need — workspace-driven persona, long-term memory, subagent orchestration, sandbox isolation, skill composition, plan mode, channel routing — into a single builder.

A bare `ReActAgent` only handles "one request → reason → tool → reply". Harness answers a different set of questions: how does the next turn pick up where the last left off, how does context stay bounded, how do users stay isolated, how do dangerous actions get reviewed, how do reusable capabilities accumulate.

> Installation, dependency, and an end-to-end "first `HarnessAgent`" walkthrough live in [Quickstart](/v2/en/docs/quickstart). This page is architecture only.

## Core working principle

Three things to keep in mind:

**1. Capabilities layer onto the reasoning loop, not into it.**
Workspace injection, compaction, subagents, sandbox, Plan Mode — each hooks into key moments of the ReAct loop. The core algorithm is untouched; Harness only adds.

**2. Capabilities don't depend on each other; they share three objects.**
Each capability does one job and is unaware of the others. They cooperate through:

- **`RuntimeContext`** — who is speaking in this call: `sessionId`, `userId`, plus arbitrary extras. Not persisted.
- **The workspace** — who reads and writes which files. Where they physically land (local disk, sandbox, KV store) is a configuration choice.
- **`AgentStateStore`** — how runtime state is restored across calls.

**3. Built-ins run in a fixed order; your middleware runs first.**
Harness wires its built-in middleware in a fixed order at build time. Anything you add via `.middleware(...)` runs **before** Harness's built-ins.

## Core components

Each capability answers one problem; opt in on the builder.

| Capability | What it solves | Builder hook | Detail |
|---|---|---|---|
| Workspace-driven persona | Persona, knowledge, subagent specs, skills, MCP allowlist all live as files | `.workspace(path)` | [Workspace](/v2/en/docs/harness/workspace) |
| State persistence | Same `(userId, sessionId)` resumes across requests, processes, replicas | on by default; override with `.stateStore(...)` | [Context & AgentState](/v2/en/docs/building-blocks/context) |
| Two-layer long-term memory | Facts in long conversations sediment into `MEMORY.md` | on by default; `.memory(...)` customizes prompts / trigger policy | [Memory](/v2/en/docs/harness/memory) |
| Conversation compaction | History bounded; force-retry on real overflow | `.compaction(...)` | [Compaction](/v2/en/docs/harness/compaction) |
| Large tool-result offloading | >80K-char results moved to disk + placeholder | `.toolResultEviction(...)` | [Compaction](/v2/en/docs/harness/compaction) |
| Subagent orchestration | Delegate to children, sync or background, with auto push-back | `.subagent(...)` or drop spec in `workspace/subagents/` | [Subagent](/v2/en/docs/harness/subagent) |
| Pluggable filesystem | Local + shell / shared store / sandbox without code changes | `.filesystem(...)` | [Filesystem](/v2/en/docs/harness/filesystem) |
| Sandbox isolation | Files and commands isolated; cross-call recovery; multi-replica | `.filesystem(new DockerFilesystemSpec()...)` | [Sandbox](/v2/en/docs/harness/sandbox) |
| Plan Mode | Read-only think-first phase with HITL exit | `.enablePlanMode()` | [Plan Mode](/v2/en/docs/harness/plan-mode) |
| Skill composition | Skills from Git / Nacos / MySQL / classpath / workspace | `.skillRepository(...)` | [Skill](/v2/en/docs/harness/skill) |
| MCP integration & tool allowlist | Declarative MCP servers + allow/deny per tool | `workspace/tools.json` | [Workspace](/v2/en/docs/harness/workspace) |
| Channel routing | Session management, per-session concurrency, multi-agent routing, streaming events | `agent.channel(...)` / `GatewayBootstrap` | [Channel](/v2/en/docs/harness/channel) |

## Progressive built-in tool loading

By default, all registered Harness built-in tools remain visible to the model. Applications with a large tool surface can opt into progressive disclosure:

```java
HarnessAgent agent = HarnessAgent.builder()
    .name("assistant")
    .model(model)
    .enableProgressiveToolLoading()
    .build();
```

This automatically enables the ungrouped `reset_equipped_tools` meta tool and places optional built-ins into inactive META groups:

| Group | Capabilities |
|---|---|
| `workspace_files` | Filesystem, shell execution and artifact delivery |
| `subagents` | Subagents, teams, background tasks and async-result waiting |
| `session_history` | Session search, listing and history |
| `web` | Web search and page fetching |

Memory, skill and Plan Mode tools stay ungrouped and visible. Existing `disable*()` options and `tools.json` filtering run first; a family with no remaining tools does not create an empty group. Custom and MCP tools are not classified automatically, even when their names match a disabled built-in. The model can activate one or more groups with `reset_equipped_tools`, and the next reasoning step receives those schemas.

Activation is isolated and persisted per session. It is not automatically reset at the end of a single call. Because changing active groups changes the model's tool-schema prefix, applications that depend heavily on prompt caching should weigh the smaller schema against reduced prefix-cache reuse.

The four group names above are reserved while this option is enabled. A build fails fast if one of those names already exists or a Harness built-in selected for progressive loading already belongs to another group; this preserves the guarantee that optional built-ins start hidden.

## How state flows

Three layers exist; the framework moves data between them automatically.

- **In-call state** — `AgentState` (conversation context, permission rules, Plan Mode state, tool state) plus `RuntimeContext` (`sessionId`, `userId`, sandbox handle, extras).
- **Cross-call state** — auto-saved at the end of every `call()` and auto-loaded on the next: the `AgentState` runtime snapshot in the configured `AgentStateStore` (default `~/.agentscope/state/<agentId>/`, addressed by `(userId, sessionId)`), the never-compacted full conversation log under `sessions/<sessionId>.log.jsonl`, subtask records, and sandbox metadata.
- **Long-term memory** — accumulated across sessions: `memory/YYYY-MM-DD.md` is append-only, periodically merged into `MEMORY.md` by a throttled background job; `MEMORY.md` is injected into the system prompt every reasoning step.

Three invariants worth remembering:

- The system prompt is rebuilt every reasoning step, so edits to `AGENTS.md` or `MEMORY.md` take effect immediately — no restart.
- Compaction, memory distillation, and background maintenance are throttled; they don't run every turn.
- `AgentState` is persisted by core's `ReActAgent` + `AgentStateStore`. Harness no longer adds its own persistence hook.

## Adding your own middleware

To insert custom behaviour without bypassing Harness's plumbing:

- Use `.middleware(...)` — your middleware runs before all Harness built-ins.
- Read `RuntimeContext` from the agent for the current call's identity (`userId` / `sessionId`).
- For workspace I/O, go through `harnessAgent.getWorkspaceManager()` — it routes correctly under sandbox or remote-store modes. `java.nio.Files` writes to the host disk and will land in the wrong place outside local mode.

## Related pages

- [Workspace](/v2/en/docs/harness/workspace) — directory layout, what gets injected into the system prompt, `tools.json`
- [Context & AgentState](/v2/en/docs/building-blocks/context) — `AgentState`, `RuntimeContext`, `AgentStateStore` persistence, multi-user isolation
- [Memory](/v2/en/docs/harness/memory) — two-layer memory
- [Compaction](/v2/en/docs/harness/compaction) — summary compaction, large-result offloading, overflow recovery
- [Filesystem](/v2/en/docs/harness/filesystem) — local + shell / shared store / sandbox
- [Sandbox](/v2/en/docs/harness/sandbox) — isolated execution, cross-call recovery, distributed
- [Subagent](/v2/en/docs/harness/subagent) — declarations, sync/background, streaming forwarding
- [Skill](/v2/en/docs/harness/skill) — four-layer composition, self-learning loop
- [Plan Mode](/v2/en/docs/harness/plan-mode) — read-only phase + HITL exit
- [Channel](/v2/en/docs/harness/channel) — session management, multi-agent routing, streaming SSE
