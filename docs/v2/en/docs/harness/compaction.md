---
title: "Context Compaction"
description: "Keep the conversation within the model's token budget without losing critical information"
---

:::{note}
This page covers **context compaction** — the strategies `HarnessAgent` uses to keep the conversation within the model's token budget. It builds on the stateless-engine design and `AgentState` persistence described in [Context & AgentState](../building-blocks/context). Read that page first if you haven't — compaction operates on the same `AgentState` that the persistence layer saves and restores.

**How they work together**: compaction mutates `AgentState.contextMutable()` in memory; the state store writes the updated `AgentState` at end of call. The two paths are independent but always run in that order — the state store sees the post-compaction state.
:::

The model's token budget is finite. A long-running conversation either compacts proactively or eventually crashes into the model's hard limit. `HarnessAgent` ships a full compaction stack — opt-in via `.compaction(...)` / `.toolResultEviction(...)`.

## What `HarnessAgent` ships

| Strategy | What it solves | When it fires | Middleware |
|------|----------|----------|--------|
| **Conversation summarization** | Context too *deep* — message count / token total piles up | Before each model reasoning call | `CompactionMiddleware` |
| **Large tool-result eviction** | Context too *wide* — a single tool result is huge | After tool execution | `ToolResultEvictionMiddleware` |
| **Overflow safety net** | Model actually returned `context_length_exceeded` | When `call()` throws | `HarnessAgent.recoverFromOverflow` |
| **Pre-summary argument truncation** | Tool-call args (e.g. `write_file` body) are big but nobody reads them later | Lightweight pre-pass before summarization | `CompactionConfig.TruncateArgsConfig` |

The four are **orthogonal — combine them freely**. All four are off by default.

### 1. Conversation summarization (`CompactionMiddleware`)

Triggers on message count or estimated token count. Distills the conversation **prefix** into a structured summary via one LLM call, **keeps the last N messages verbatim**, and writes `[summary] + [recent tail]` back into `AgentState.contextMutable()`.

```java
HarnessAgent.builder()
    .compaction(CompactionConfig.builder()
        .triggerMessages(30)     // fire at 30 messages
        .keepMessages(10)        // keep last 10 verbatim
        .build())
    .build();
```

The default summary prompt organizes content into `SESSION INTENT / SUMMARY / ARTIFACTS / NEXT STEPS` — works well for engineering/orchestration agents. `CompactionConfig` also supports `.model(...)` to specify a dedicated model for the summarization LLM call (falls back to the agent's primary model when not set). The full configuration surface (`triggerTokens`, `keepTokens`, `flushBeforeCompact`, `offloadBeforeCompact`, `model`, `TruncateArgsConfig`) and the summary prompt template are in [Memory — Enable compaction](./memory#enable-compaction); not duplicated here.

### 2. Large tool-result eviction (`ToolResultEvictionMiddleware`)

Independent of summarization. When a tool result exceeds the threshold (default 80K chars ≈ 20K tokens), the full output is written to a workspace directory and **the in-context message is replaced with a head + tail preview (~2K chars each) plus a `read_file` pointer**. The agent reads the full version on demand.

```java
HarnessAgent.builder()
    .toolResultEviction(ToolResultEvictionConfig.defaults())
    .build();
```

`read_file` / `write_file` / `edit_file` / `grep_files` / `glob_files` / `list_files` / `memory_*` / `session_search` are excluded by default — they either self-paginate or return tiny payloads. **Shell `execute` is deliberately NOT excluded** because command output can be arbitrarily large.

Details in [Memory — Large tool-result offloading](./memory#large-tool-result-offloading).

### 3. Overflow safety net

If the model returns `context_length_exceeded` / `maximum context` / `token limit` errors, `HarnessAgent.recoverFromOverflow()` runs a forced `triggerMessages=1` extreme compaction and **automatically retries once**. Requires `.compaction(...)` to be configured at build time — otherwise the error propagates.

No extra configuration: turn on compaction, and overflow recovery comes along.

### 4. Pre-summary argument truncation (optional)

Before the LLM summary pass, a **non-LLM** string-truncation pass clips oversized tool-call args (`write_file`, `edit_file` bodies):

```java
CompactionConfig.builder()
    .triggerMessages(80)
    .truncateArgs(CompactionConfig.TruncateArgsConfig.builder()
        .maxArgLength(2000)
        .truncationText("... [truncated] ...")
        .build())
    .build();
```

In many workloads this single step delays the summarization trigger considerably at near-zero cost.

## Coordination with Memory

`CompactionConfig.flushBeforeCompact` (default `true`) decides **whether to extract facts from the conversation prefix into long-term memory before summarizing** — handled by `MemoryFlushMiddleware` + `MemoryFlushManager`, which read `<workspace>/MEMORY.md` and `memory/*.md` and incrementally append new facts. Once summarization drops the prefix messages, the information persists: the agent can pull it back via `memory_search` / `memory_get`.

Similarly, `offloadBeforeCompact` (default `true`) writes the **raw messages** to the uncompressed `*.log.jsonl` before summarization, so `session_search` can still reach them.

> The full Memory subsystem — two-tier structure, background maintenance (archive, merge), memory tools — is in [Memory](./memory). Compaction and memory are commonly used together but have independent switches.

## What compaction does *not* touch

`ConversationCompactor` only operates on the **conversation message list** in `AgentState.contextMutable()`. The following live in other `AgentState` fields and **stay untouched by summarization**:

- **Plan Mode state** (`AgentState.getPlanModeContext()`): whether plan mode is active, current plan file path. The plan file itself lives under `plans/` in the workspace and is managed by Plan Mode's own lifecycle. See [Plan Mode](./plan-mode).
- **Subagent background tasks** (`task_id`, status, result): stored at `<workspace>/agents/<parentAgentId>/tasks/<sessionId>.json`, managed by `TaskRepository`; completed results are injected back into the parent via a system reminder on the next reasoning turn — they **do not enter the conversation message stream**, so summarization can't touch them. See [Subagent — Background task storage](./subagent#background-task-storage).
- **`todo_write` task list** (`AgentState.getTasksContext()`): independent field, persisted with `AgentState` but not in the compaction path. See [Plan Mode — Interaction with `todo_write`](./plan-mode#interaction-with-todo_write).
- **Permission rules** (`getPermissionContext()`): independent field, self-persisting.

Each of these owns its own state machine and recovery path; the compaction track is transparent to them — you can enable `.compaction(...)` without worrying about losing a plan or an in-flight background task.

## Letting the agent inspect its own history

When session capability is on (the default), three query tools are registered automatically:

- `session_list agentId="..."` — list an agent's historical sessions.
- `session_history agentId="..." sessionId="..." lastN=20` — recent N messages of a session.
- `session_search query="..." agentId="..."` — keyword search across history.

These tools read the **uncompressed conversation log** (`<workspace>/agents/<agentId>/sessions/<sessionId>.log.jsonl`), so even when the in-context conversation has been summarized, the agent can still pull up the original messages.

---

## Related pages

- [Context & AgentState](../building-blocks/context) — stateless engine design, `AgentState` structure, state persistence, `RuntimeContext`
- [Architecture](./architecture) — how context, state persistence, and workspace cooperate inside one call
- [Memory](./memory) — long-term memory, full compaction configuration, large tool-result offloading, background maintenance
- [Plan Mode](./plan-mode) — independent persistence and recovery of plan state
- [Subagent](./subagent) — where background tasks live and how they survive node migration
- [Filesystem](./filesystem) — `userId`-based multi-tenant path isolation
