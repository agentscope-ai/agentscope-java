---
title: "Harness Architecture"
description: "What HarnessAgent is, how its capabilities cooperate, and how state flows during a call()"
---

`HarnessAgent` is a thin wrapper around `ReActAgent` that packages the engineering capabilities long-running agents need — workspace-driven persona, long-term memory, subagent orchestration, sandbox isolation, skill composition, plan mode — into a single builder.

A bare `ReActAgent` only handles "one request → reason → tool → reply". Harness answers a different set of questions: how does the next turn pick up where the last left off, how does context stay bounded, how do users stay isolated, how do dangerous actions get reviewed, how do reusable capabilities accumulate.

> Installation, dependency, and an end-to-end "first `HarnessAgent`" walkthrough live in [Quickstart](../quickstart.md). This page is architecture only.

## Core working principle

Three things to keep in mind:

**1. Capabilities layer onto the reasoning loop, not into it.**
Workspace injection, compaction, subagents, sandbox, Plan Mode — each hooks into key moments of the ReAct loop. The core algorithm is untouched; Harness only adds.

**2. Capabilities don't depend on each other; they share three objects.**
Each capability does one job and is unaware of the others. They cooperate through:

- **`RuntimeContext`** — who is speaking in this call: `sessionId`, `userId`, plus arbitrary extras. Not persisted.
- **The workspace** — who reads and writes which files. Where they physically land (local disk, sandbox, KV store) is a configuration choice.
- **`Session`** — how runtime state is restored across calls.

**3. Built-ins run in a fixed order; your middleware runs first.**
Harness wires its built-in middleware in a fixed order at build time. Anything you add via `.middleware(...)` runs **before** Harness's built-ins.

## Core components

Each capability answers one problem; opt in on the builder.

| Capability | What it solves | Builder hook | Detail |
|---|---|---|---|
| Workspace-driven persona | Persona, knowledge, subagent specs, skills, MCP allowlist all live as files | `.workspace(path)` | [Workspace](./workspace) |
| Session persistence | Same `sessionId` resumes across requests, processes, replicas | on by default; override with `.session(...)` | [Context](./context) |
| Two-layer long-term memory | Facts in long conversations sediment into `MEMORY.md` | `.compaction(...)` | [Memory](./memory) |
| Conversation compaction | History bounded; force-retry on real overflow | `.compaction(...)` | [Memory](./memory) |
| Large tool-result offloading | >80K-char results moved to disk + placeholder | `.toolResultEviction(...)` | [Memory](./memory) |
| Subagent orchestration | Delegate to children, sync or background, with auto push-back | `.subagent(...)` or drop spec in `workspace/subagents/` | [Subagent](./subagent) |
| Pluggable filesystem | Local + shell / shared store / sandbox without code changes | `.filesystem(...)` | [Filesystem](./filesystem) |
| Sandbox isolation | Files and commands isolated; cross-call recovery; multi-replica | `.filesystem(new DockerFilesystemSpec()...)` | [Sandbox](./sandbox) |
| Plan Mode | Read-only think-first phase with HITL exit | `.enablePlanMode()` | [Plan Mode](./plan-mode) |
| Skill composition | Skills from Git / Nacos / MySQL / classpath / workspace | `.skillRepository(...)` | [Skill](./skill) |
| MCP integration & tool allowlist | Declarative MCP servers + allow/deny per tool | `workspace/tools.json` | [Workspace](./workspace) |

## How state flows

Three layers exist; the framework moves data between them automatically.

- **In-call state** — `AgentState` (conversation context, permission rules, Plan Mode state, tool state) plus `RuntimeContext` (`sessionId`, `userId`, sandbox handle, extras).
- **Cross-call state** — auto-saved at the end of every `call()` and auto-loaded on the next: the runtime snapshot under `agents/<agentId>/context/<sessionId>/`, the never-compacted full conversation log under `sessions/<sessionId>.log.jsonl`, subtask records, and sandbox metadata.
- **Long-term memory** — accumulated across sessions: `memory/YYYY-MM-DD.md` is append-only, periodically merged into `MEMORY.md` by a throttled background job; `MEMORY.md` is injected into the system prompt every reasoning step.

Three invariants worth remembering:

- The system prompt is rebuilt every reasoning step, so edits to `AGENTS.md` or `MEMORY.md` take effect immediately — no restart.
- Compaction, memory distillation, and background maintenance are throttled; they don't run every turn.
- `AgentState` is persisted by core's `ReActAgent` + `Session`. Harness no longer adds its own persistence hook.

## Adding your own middleware

To insert custom behaviour without bypassing Harness's plumbing:

- Use `.middleware(...)` — your middleware runs before all Harness built-ins.
- Read `RuntimeContext` from the agent for the current call's identity (`userId` / `sessionId`).
- For workspace I/O, go through `harnessAgent.getWorkspaceManager()` — it routes correctly under sandbox or remote-store modes. `java.nio.Files` writes to the host disk and will land in the wrong place outside local mode.

## Related pages

- [Workspace](./workspace) — directory layout, what gets injected into the system prompt, `tools.json`
- [Context](./context) — `AgentState`, `RuntimeContext`, `Session` persistence, multi-user isolation
- [Memory](./memory) — two-layer memory, compaction, large-result offloading
- [Filesystem](./filesystem) — local + shell / shared store / sandbox
- [Sandbox](./sandbox) — isolated execution, cross-call recovery, distributed
- [Subagent](./subagent) — declarations, sync/background, streaming forwarding
- [Skill](./skill) — four-layer composition, self-learning loop
- [Plan Mode](./plan-mode) — read-only phase + HITL exit
