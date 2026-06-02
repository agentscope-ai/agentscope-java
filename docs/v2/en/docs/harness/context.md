---
title: "Context & Session"
description: "Persist agent runtime state across machines, and keep conversation context within the model's token budget"
---

`HarnessAgent` manages working memory along two cooperating tracks:

1. **Session — persist agent runtime state**, so the same `sessionId` can resume in another process or on another machine.
2. **Context compaction — keep the conversation within the model's token budget**, without losing the information the agent still needs.

Both tracks share one data structure: `AgentState`. Compaction mutates it in memory; Session writes it out at end of call. The two sections below cover each track in turn.

---

## Part 1: Session — persistence of agent runtime state

### What Session stores —— `AgentState`

Session persists an **`AgentState`** (`io.agentscope.core.state.AgentState`) — a complete snapshot of everything that makes the agent restartable:

| `AgentState` field | Content |
|---|---|
| `getContext()` / `contextMutable()` | Current conversation history (user / assistant / tool calls / tool results) |
| `getSummary()` | Compacted summary (when compaction is enabled) |
| `getPermissionContext()` | Tool permission rules — see [Permissions](../building-blocks/permission-system.md) |
| `getPlanModeContext()` | Whether Plan Mode is active, current plan file path |
| `getTasksContext()` | The `todo_write` task list |
| `getToolContext()` | Active toolkit groups (`activatedGroups`) |

At the end of each `call()`, the framework writes the entire `AgentState` to Session under the key `agent_state`. On construction, the next `call()` with the same `sessionId` loads it back automatically. **Provided the Session backend is distributed (e.g. Redis), agent instances on different processes — even different physical machines — see identical state.**

### The auto-persistence and recovery flow

```
agent starts ─► loadOrCreateAgentState(session, sessionKey)
                │
                ├─ agent_state present in Session ─► restore it
                └─ absent ─► construct fresh AgentState

agent.call() running ─► middlewares mutate AgentState.contextMutable()
                        (compaction, Plan, todo_write, permissions, …)

process exits / interrupt ─► shutdownManager fires
                             session.save(sessionKey, "agent_state", state)
```

This wiring lives in `ReActAgent` itself (`loadOrCreateAgentState` + `shutdownManager.bindStateSaver`); `HarnessAgent` inherits it for free.

> Mid-`call()` state changes happen against the in-memory `AgentState`. **Session is written once per call (and on shutdown), not on every message** — so the throughput pressure on your Session backend stays low.

### Built-in and extension implementations

Anything implementing `io.agentscope.core.session.Session` works. Pick by deployment shape:

| Implementation | Module | Use case |
|---|---|---|
| `InMemorySession` | `agentscope-core` | Unit tests / single-process demos; lost on exit |
| `JsonSession` | `agentscope-core` | Local dev with file persistence; not cross-node |
| `WorkspaceSession` | `agentscope-harness` | **`HarnessAgent` default**, built on `JsonSession`, writes under `<workspace>/agents/<agentId>/context/<sessionId>/`; **single-host single-tenant** |
| `RedisSession` | `agentscope-extensions-session-redis` | **Production default** for multi-replica deployments; supports Jedis / Lettuce / Redisson (Standalone / Cluster / Sentinel) |
| `MysqlSession` | `agentscope-extensions-session-mysql` | When session data needs to flow into a relational store (audit, reporting) |

Switching is one call at builder time:

```java
// Default (single host) — omit .session(...); WorkspaceSession is used automatically
HarnessAgent agent = HarnessAgent.builder()
    .name("MyAgent")
    .model(model)
    .workspace(workspace)
    .build();

// Production multi-replica — swap in RedisSession
RedisClient client = RedisClient.create("redis://redis.prod:6379");
HarnessAgent agent = HarnessAgent.builder()
    .name("MyAgent")
    .model(model)
    .workspace(workspace)
    .session(RedisSession.builder().lettuceClient(client).build())
    .build();
```

:::{warning}
`WorkspaceSession` is single-host only. If you've already chosen `filesystem(SandboxFilesystemSpec)` or `filesystem(RemoteFilesystemSpec)` (distributed workspace), HarnessAgent **rejects** a `WorkspaceSession` at build time with `IllegalStateException` — sandbox state must be shared across replicas.
:::

### Real-time resume across processes and machines

Once Session is distributed (e.g. Redis), cross-machine resume is **automatic**:

```java
// Node A — start a conversation
HarnessAgent agentA = HarnessAgent.builder()
    .session(redisSession)
    /* ... */ .build();
agentA.call(msg, RuntimeContext.builder()
    .sessionId("alice-2026-06-02-001")
    .userId("alice")
    .build()).block();

// Node B — different physical machine, separate JVM
HarnessAgent agentB = HarnessAgent.builder()
    .session(redisSession)
    /* same sessionId, same sessionKey */ .build();

// Node B's first call() loads the AgentState node A left in Redis
agentB.call(nextMsg, RuntimeContext.builder()
    .sessionId("alice-2026-06-02-001")
    .userId("alice")
    .build()).block();
```

This buys you:

- **Failover**: a crashed node — conversations migrate to a healthy one, user notices nothing.
- **Rolling deploys**: old pods save on shutdown, new pods load on first call — **conversations never break across releases**.
- **Cross-surface continuity**: a user starts in the Web UI, switches to the CLI — same `sessionId`, all memory present.

`SessionKey` defines the namespacing. `SimpleSessionKey.of(sessionId)` is enough for most cases; implement your own when you need `(tenantId, userId, agentId, sessionId)` keying.

### Multi-user isolation

`sessionId` and `userId` solve different problems:

- **`sessionId`** — which conversation this is; independent `AgentState` snapshot.
- **`userId`** — which user's namespace files land in; see [Filesystem](./filesystem).

```java
agent.call(msg, RuntimeContext.builder()
    .sessionId("alice-1").userId("alice").build()).block();

agent.call(msg, RuntimeContext.builder()
    .sessionId("bob-1").userId("bob").build()).block();
```

Two users — separate state, separate filesystem paths, no crosstalk. For `AgentState`-level user isolation in production, encode `userId` into `SessionKey` (with `RedisSession` it becomes part of the Redis key) rather than relying on filesystem path bucketing.

### Reading and writing `AgentState` directly

When you need to bypass the agent loop (admin console, audit, batch migration):

```java
import io.agentscope.core.state.AgentState;

AgentState state = agent.getAgentState();
System.out.println("messages: " + state.getContext().size());

String json = state.toJson();
AgentState restored = AgentState.fromJsonString(json);
```

| Method | Description |
|------|------|
| `getContext()` | Current conversation history (immutable view) |
| `contextMutable()` | Writable view, use with care |
| `setSummary(...)` / `getSummary()` | Custom compaction summary (for your own compaction middleware) |
| `toJson()` / `fromJsonString(String)` | Serialize / deserialize |

:::{note}
The 1.0 `Memory` interface (`InMemoryMemory` / `LongTermMemory`, etc.) is `@Deprecated(forRemoval = true)` in 2.0. New code should use `AgentState.getContext()` + `Session`; `Memory` remains only as a source-compat shim.
:::

### Letting the agent inspect its own history

When session capability is on (the default), three query tools are registered automatically:

- `session_list agentId="..."` — list an agent's historical sessions.
- `session_history agentId="..." sessionId="..." lastN=20` — recent N messages of a session.
- `session_search query="..." agentId="..."` — keyword search across history.

These tools read the **uncompressed conversation log** (`<workspace>/agents/<agentId>/sessions/<sessionId>.log.jsonl`), so even when the in-context conversation has been summarized, the agent can still pull up the original messages.

---

## Part 2: Context compaction strategies

The model's token budget is finite. A long-running conversation either compacts proactively or eventually crashes into the model's hard limit. `HarnessAgent` ships a full compaction stack — opt-in via `.compaction(...)` / `.toolResultEviction(...)`.

**Core invariant**: **compaction mutates `AgentState` in memory; Session writes the updated `AgentState` at end of call**. Compaction and persistence are independent paths that always run in that order — Session sees the post-compaction state.

### What `HarnessAgent` ships

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

The default summary prompt organizes content into `SESSION INTENT / SUMMARY / ARTIFACTS / NEXT STEPS` — works well for engineering/orchestration agents. The full configuration surface (`triggerTokens`, `keepTokens`, `flushBeforeCompact`, `offloadBeforeCompact`, `TruncateArgsConfig`) and the summary prompt template are in [Memory — Enable compaction](./memory#enable-compaction); not duplicated here.

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

### Coordination with Memory

`CompactionConfig.flushBeforeCompact` (default `true`) decides **whether to extract facts from the conversation prefix into long-term memory before summarizing** — handled by `MemoryFlushMiddleware` + `MemoryFlushManager`, which read `<workspace>/MEMORY.md` and `memory/*.md` and incrementally append new facts. Once summarization drops the prefix messages, the information persists: the agent can pull it back via `memory_search` / `memory_get`.

Similarly, `offloadBeforeCompact` (default `true`) writes the **raw messages** to the uncompressed `*.log.jsonl` before summarization, so `session_search` can still reach them.

> The full Memory subsystem — two-tier structure, background maintenance (archive, merge), memory tools — is in [Memory](./memory). Compaction and memory are commonly used together but have independent switches.

### What compaction does *not* touch

`ConversationCompactor` only operates on the **conversation message list** in `AgentState.contextMutable()`. The following live in other `AgentState` fields and **stay untouched by summarization**:

- **Plan Mode state** (`AgentState.getPlanModeContext()`): whether plan mode is active, current plan file path. The plan file itself lives under `plans/` in the workspace and is managed by Plan Mode's own lifecycle. See [Plan Mode](./plan-mode).
- **Subagent background tasks** (`task_id`, status, result): stored at `<workspace>/agents/<parentAgentId>/tasks/<sessionId>.json`, managed by `TaskRepository`; completed results are injected back into the parent via a system reminder on the next reasoning turn — they **do not enter the conversation message stream**, so summarization can't touch them. See [Subagent — Background task storage](./subagent#background-task-storage).
- **`todo_write` task list** (`AgentState.getTasksContext()`): independent field, persisted with `AgentState` but not in the compaction path. See [Plan Mode — Interaction with `todo_write`](./plan-mode#interaction-with-todo_write).
- **Permission rules** (`getPermissionContext()`): independent field, self-persisting.

Each of these owns its own state machine and recovery path; the compaction track is transparent to them — you can enable `.compaction(...)` without worrying about losing a plan or an in-flight background task.

---

## Appendix: `RuntimeContext` — per-call metadata

`RuntimeContext` (in `io.agentscope.core.agent`) is a lightweight per-call carrier passed to `agent.call(msgs, ctx)`; hooks and tools share it for the duration of one call. **Not persisted. Does not participate in Session.**

```java
import io.agentscope.core.agent.RuntimeContext;

RuntimeContext ctx = RuntimeContext.builder()
        .userId("alice")
        .sessionId("s-001")
        .put("request_id", "req-2026-06-01-abc")
        .put(MyTenantInfo.class, new MyTenantInfo("tenant-7"))
        .build();

Msg result = agent.call(List.of(new UserMessage("Hi")), ctx).block();
```

Available accessors:

| Method | Description |
|------|------|
| `getSessionId()` / `getUserId()` / `getSessionKey()` | Built-in fields used to route session and tenant |
| `get(String)` / `put(String, Object)` | String-keyed get/put |
| `get(Class<T>)` / `put(Class<T>, T)` | Typed singleton get/put |
| `getExtra()` | Direct access to the string-attribute map (mutable view) |
| `RuntimeContext.empty()` | Empty context |

:::{tip}
**The Session backend is bound at builder time and cannot be switched per call via `RuntimeContext`.** For per-user Session isolation, use `userId` + `SessionKey` (or a custom `keyPrefix`); do not try to hand each call a different Session instance.
:::

---

## Related pages

- [Architecture](./architecture) — how Context, Session, and workspace cooperate inside one call
- [Memory](./memory) — long-term memory, full compaction configuration, large tool-result offloading, background maintenance
- [Plan Mode](./plan-mode) — independent persistence and recovery of plan state
- [Subagent](./subagent) — where background tasks live and how they survive node migration
- [Filesystem](./filesystem) — `userId`-based multi-tenant path isolation
- [Permissions](../building-blocks/permission-system.md) — persistence of permission rules
