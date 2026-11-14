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

## Optional web tools

The built-in `web_search` (Tavily) and `web_fetch` tools are disabled by default:

- **No unusable capabilities in model input.** `web_search` requires `TAVILY_API_KEY`. Without it, the tool cannot search, but its schema still consumes input tokens if registered.
- **Fewer wasted turns.** Selecting an unconfigured tool produces a configuration error instead of results, adding avoidable tool-call tokens, latency and error context; the model may spend further turns trying to recover.
- **No unsolicited alternative to native search.** A provider's built-in search may already be available. Automatically exposing another `web_search` can create naming or selection conflicts and steer the model away from the application's intended search backend.
- **Explicit external-service choice.** Tavily has separate credentials, billing and data-handling terms. Applications should deliberately choose whether to send search queries to that service rather than inherit the integration as a framework default.

Enable these tools explicitly when needed:

```java
HarnessAgent agent = HarnessAgent.builder()
    .model(model)
    .workspace(workspace)
    .enableWebTools()
    .build();
```

Automatically constructed general-purpose and declared subagents inherit this setting. `.disableWebTools()` turns the tools off again; `.webHttpClient(...)` only customizes their HTTP client and does not enable them. Setting `TAVILY_API_KEY` alone does not enable them either. Tools registered explicitly in your toolkit and model-provider-native search are unaffected.

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
