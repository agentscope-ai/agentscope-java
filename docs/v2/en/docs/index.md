---
title: "What's AgentScope 2.0?"
description: "Harness engineering, enterprise-grade distributed deployment, and a redesigned foundation."
---

AgentScope Java 2.0 is a major step up from a "build an agent" toolkit toward a complete platform for **running agents in production**. The improvements fall into three focus areas, each solving a distinct problem.

:::{note}
AgentScope Java 2.0 aims to preserve compatibility with 1.x where possible so that most users can upgrade smoothly, but it also introduces API-level breaking changes alongside significant improvements to the core abstractions, APIs, and architecture. See the [V1 Migration Guide](./change-log.md) for the full migration guide.
:::

## 1 · Harness Engineering — built for long-running, complex tasks

A bare ReAct loop solves one reasoning turn. Real-world tasks run for hours, accumulate state, and demand persistent skills. **Harness** builds the engineering scaffolding so agents stay reliable indefinitely, accrue capability, and finish complex jobs without manual context surgery — the reasoning core stays untouched, capabilities layer on.

::::{grid} 2

:::{grid-item-card} Self-evolution & skill repository
:link: harness/skill.html

Successful patterns are saved as Markdown skills in `workspace/skills/`, loaded per step and shared across sessions — know-how accumulates between runs.
:::

:::{grid-item-card} Layered memory management
:link: harness/memory.html

Three tiers: in-context conversation, agent-curated `MEMORY.md`, and an on-disk fact log. Auto-compaction bounds the prompt; `memory_*` tools give explicit recall.
:::

:::{grid-item-card} Sub-agents
:link: harness/subagent.html

Declare child agents in Markdown specs; run them sync or in the background via `agent_spawn` / `agent_send`. Background results push back via `system-reminder` — no polling.
:::

:::{grid-item-card} Auto context management
:link: harness/compaction.html

Structured compaction preserves goals, state, findings, and next steps; oversized tool results offload to disk; context-overflow retry is the safety net.
:::

:::{grid-item-card} Plan Mode for complex tasks
:link: harness/plan-mode.html

A read-only planning state for long tasks; plans persist under `workspace/plans/` and drive execution, keeping intent decoupled from action.
:::

:::{grid-item-card} Workspace as the foundation
:link: harness/workspace.html

Persona, knowledge, skills, sub-agent specs, session logs — all on-disk Markdown / JSON, auto-injected into the prompt each turn.
:::

::::

## 2 · Enterprise-grade distributed deployment

Production agents must serve many tenants, run untrusted tool code safely, and survive rolling restarts without losing in-flight context. AgentScope 2.0 is built for **stateless horizontal scaling**: any replica can pick up the full context of any user, sandbox state resumes across processes, and permission gates plus multi-dimension isolation keep every tenant's data separate.

::::{grid} 2

:::{grid-item-card} Multi-tenant isolation
:link: building-blocks/context.html

Isolate state across `session` / `user` / `agent` / `org`. `RuntimeContext` keys flow through workspace paths, KV namespaces, and sandbox state slots.
:::

:::{grid-item-card} Secure sandbox execution
:link: harness/sandbox.html

Tools execute in an isolated environment — local subprocess, Docker, or remote AgentRun — with snapshot/resume so long jobs survive restarts.
:::

:::{grid-item-card} Tool permission control
:link: building-blocks/permission-system.html

Three-state engine (allow / approve / deny) over static rules, tool category, and input analysis. Sensitive tools require human approval — HITL is first-class.
:::

:::{grid-item-card} Graceful start / stop & session recovery
:link: building-blocks/context.html

The same `(userId, sessionId)` resumes the full conversation on any process. `AgentStateStore` (in-memory / JSON file / MySQL / Redis) backs zero-downtime rolling deploys and crash recovery.
:::

::::

## 3 · Foundation framework — a leaner, more developer-friendly core

The bottom layer has been redesigned. Messages, events, and the extension model are now smaller, more orthogonal, and more pleasant to work with — and HITL plus event streaming are no longer add-ons grafted on top, they are part of how the framework runs.

::::{grid} 2

:::{grid-item-card} Event streaming, built in
:link: building-blocks/message-and-event.html

Every step — model call, text delta, tool execution, tool result — surfaces as a typed event on one stream. Subscribe once; your UI follows in real time.
:::

:::{grid-item-card} A simpler message model
:link: building-blocks/message-and-event.html

Text, files, images, audio, video, thinking, tool results — all one `ContentBlock` shape. Role-strict construction catches malformed messages at build time.
:::

:::{grid-item-card} Middleware, not hooks
:link: building-blocks/middleware.html

Five stages (`onAgent` / `onReasoning` / `onActing` / `onModelCall` / `onSystemPrompt`) replace v1's loose hooks. Each concern stays in its own layer and composes cleanly.
:::

:::{grid-item-card} Human-in-the-loop, first class
:link: building-blocks/permission-system.html

Confirm tool arguments, approve sensitive actions, or hand off mid-run to an external system. The agent pauses and resumes exactly where it stopped — no scaffolding.
:::

::::

---

If you are still evaluating whether to migrate, the [V1 Migration Guide](./change-log.md) breaks down every change with a Migration Guide (must-do vs. recommended) and a What's New section — enough to plan an upgrade end-to-end. For per-version changes, see [Release Notes](others/release-notes.md).
