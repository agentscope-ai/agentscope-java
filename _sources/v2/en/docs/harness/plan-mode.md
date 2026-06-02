---
title: "Plan Mode"
description: "Think before acting: a read-only phase that writes a plan file and requires HITL approval before executing"
---

## Role

Plan Mode lets the agent "figure out and write down intent" before executing. While active, the agent is in a **read-only phase**:

- Only **read-only tools** plus 4 whitelisted tools work: `plan_enter` / `plan_write` / `plan_exit` / `todo_write`.
- Any other tool call is rejected immediately (the agent sees a "plan-mode denied" note).
- Exiting Plan Mode requires HITL confirmation (reusing the permission system's ASK), so the model can't unilaterally jump into execution.

This pipeline encodes "design → plan → human review → execute" — combined with `todo_write` and subagents, it noticeably reduces "improvise-then-break-things" outcomes on long tasks.

## Opt-in

```java
HarnessAgent agent = HarnessAgent.builder()
    .name("planner")
    .model(model)
    .workspace(workspace)
    .enablePlanMode()                          // installs the Plan Mode trio
    .planFileDirectory("plans")                // optional; default "plans"
    .build();
```

Builder options:

| Method | Default | Notes |
|--------|---------|-------|
| `enablePlanMode()` / `enablePlanMode(boolean)` | `false` | enable Plan Mode |
| `planFileDirectory(String)` | `"plans"` | plan-file root (workspace-relative) |

You can also call `enableTaskList()` so that todos created during the plan phase show up as a small reminder before each reasoning step.

## The three tools

| Tool | Purpose | Params |
|------|---------|--------|
| `plan_enter` | Enter Plan Mode | none |
| `plan_write` | Write content to the current plan file (default `plans/PLAN.md`) | `content` |
| `plan_exit` | Exit Plan Mode → execution phase; HITL confirmation | `rationale` (optional) |

`plan_write` is **a dedicated write entry for Plan Mode** — avoids the security risk of whitelisting the generic `write_file` (which would let the model write anywhere during plan).

## Workflow

```{mermaid}
sequenceDiagram
    autonumber
    participant U as User
    participant A as Agent
    participant H as Human (HITL)
    participant FS as workspace

    U->>A: "Refactor module X for me"
    A->>A: plan_enter
    A->>A: think → call read_file / grep_files (read-only)
    A->>FS: plan_write to plans/PLAN.md
    A->>H: plan_exit → HITL confirmation
    H-->>A: ConfirmResult(true)
    A->>A: enter execution phase; all tools allowed
```

Any non-whitelisted tool call (e.g. `write_file` / `execute`) during the plan phase is rejected immediately with something like:

```text
[Tool denied — plan mode is active]
Only read-only tools and plan_enter / plan_write / plan_exit / todo_write are allowed.
```

Seeing the denial, the model naturally switches back to "write the plan first".

## Plan state is persisted

Plan Mode is **runtime state** and is auto-persisted along with `AgentState` — process restarts, node failovers, and cross-replica restores all bring back the plan phase. The plan file itself is written to `plans/` in the workspace and goes through whichever filesystem mode you've configured (local / sandbox / remote KV), so it's distributed-safe.

## Programmatic enter/exit

When app code drives Plan Mode (e.g. an admin console button):

```java
agent.enterPlanMode();    // equivalent to the LLM calling plan_enter
agent.exitPlanMode();     // equivalent to plan_exit; programmatic entry does NOT trigger HITL
agent.isPlanModeActive();
```

If you use `agentscope-admin-spring-boot-starter`, the admin HTTP API also exposes Plan Mode controls (`POST /v1/admin/sessions/{id}:enter-plan-mode` / `:exit-plan-mode` / `GET /v1/admin/sessions/{id}/plan`).

## Interaction with subagents

⚠ Current **known gap**: subagents spawned via `agent_spawn` during Plan Mode **do not automatically inherit the read-only restriction**. To restrict the child:

- Narrow `tools` in the child's declaration to a read-only set, or
- Also `enablePlanMode()` on the child's own builder and enter it explicitly

A future release will propagate plan-mode restrictions parent → child automatically.

## Interaction with `todo_write`

Plan Mode and `todo_write` (provided by core) are **independent but commonly used together**:

- **Plan Mode** — phase switch + plan file + HITL exit
- **`todo_write`** — maintain a structured "what to do now" list during execution (whole-list replace; exactly one `in_progress`)

Typical workflow: write `PLAN.md` during the plan phase → `plan_exit` → in execution use `todo_write` to slice the PLAN into 5–8 todos → progress one at a time. Each reasoning step shows the agent a todos reminder to stay focused.

⚠ Don't confuse with subagent **background tasks** (`task_output` / `task_cancel` / `task_list`) — that's a different concept; see [Subagent](./subagent).

## Related Pages

- [Workspace](./workspace) — `plans/` directory location
- [Subagent](./subagent) — `todo_write` ≠ subagent task; don't confuse them
- [Architecture](./architecture) — where Plan Mode sits in the call() timeline
