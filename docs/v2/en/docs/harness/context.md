---
title: "Context & State Persistence"
description: "Persist agent runtime state across machines, and keep conversation context within the model's token budget"
---

`HarnessAgent` manages working memory along two cooperating tracks:

1. **State persistence — persist agent runtime state** via an `AgentStateStore`, so the same `(userId, sessionId)` can resume in another process or on another machine.
2. **Context compaction — keep the conversation within the model's token budget**, without losing the information the agent still needs.

Both tracks share one data structure: `AgentState`. Compaction mutates it in memory; the state store writes it out at end of call. The two sections below cover each track in turn.

---

## Stateless Agent Engine

`ReActAgent` (and `HarnessAgent` that wraps it) is designed as a **stateless engine**: the agent instance itself holds only immutable configuration — system prompt, model, tools, middleware chain — while all per-session mutable data lives in `AgentState`, indexed by `(userId, sessionId)`. A single agent instance can concurrently serve many users and sessions; the caller simply passes a different `RuntimeContext` on each `call()`.

```
┌──────────────────────────────────────────────────────────────────┐
│                     HarnessAgent (singleton)                     │
│  Immutable config: sysPrompt, model, toolkit, middlewares        │
│                                                                  │
│  ┌─ state cache ─────────────────────────────────────────────┐   │
│  │  ("alice","s1") → AgentState  ← call(…, RC(alice,s1))       │
│  │  ("bob","s2")   → AgentState  ← call(…, RC(bob,s2))        │
│  └───────────────────────────────────────────────────────────┘   │
│                                                                  │
│  per-session gate: same (uid,sid) calls serialised, others ∥     │
└──────────────────────────────────────────────────────────────────┘
```

### What this means for you

- **No agent-per-user registry.** One `HarnessAgent` instance can serve all your users — just vary `RuntimeContext.userId` and `RuntimeContext.sessionId` per request.
- **Concurrency is built in.** Different `(userId, sessionId)` pairs run fully in parallel; the same pair is automatically serialised to preserve conversation consistency.
- **State is fully internal.** The agent loads `AgentState` from the store at call entry and saves it at call exit — the caller never manages state objects directly.
- **Per-call isolation.** Each `call()` works on its own `AgentState` snapshot. Middleware and tools access the call-scoped state via `RuntimeContext.getAgentState()` (injected by the framework at call entry), so concurrent calls never see each other's state.

---

## Part 1: State persistence of agent runtime state

### What the state store persists — `AgentState`

An [`AgentStateStore`](../../integration/session/index.md) persists an **`AgentState`** (`io.agentscope.core.state.AgentState`) — a complete snapshot of everything that makes the agent restartable:

| `AgentState` field | Content |
|---|---|
| `getSessionId()` | The session identifier this state belongs to |
| `getUserId()` | The user identifier (nullable for anonymous sessions) |
| `getContext()` / `contextMutable()` | Current conversation history (user / assistant / tool calls / tool results) |
| `getSummary()` | Compacted summary (when compaction is enabled) |
| `getPermissionContext()` | Tool permission rules — see [Permissions](../building-blocks/permission-system.md) |
| `getPlanModeContext()` | Whether Plan Mode is active, current plan file path |
| `getTasksContext()` | The `todo_write` task list |
| `getToolContext()` | Active toolkit groups (`activatedGroups`) |

`AgentState` also carries a transient, non-serialised `InterruptControl` for per-session interrupt signalling — see [Per-session interrupt](#per-session-interrupt) below.

At the end of each `call()`, the framework writes the entire `AgentState` to the state store under the key `agent_state`, addressed by the call's `(userId, sessionId)`. The next `call()` with the same `(userId, sessionId)` loads it back automatically. **Provided the state store is distributed (e.g. Redis), agent instances on different processes — even different physical machines — see identical state.**

### The auto-persistence and recovery flow

```
call(msgs, RuntimeContext(userId, sessionId))
  │
  ├─ per-session gate: serialise same (uid, sid), others run in parallel
  │
  ▼
  load AgentState from cache or stateStore
  │   inject onto RuntimeContext: rc.setAgentState(state)
  │
  ▼
  reasoning loop
  │   middlewares mutate state.contextMutable()
  │   (compaction, Plan, todo_write, permissions, …)
  │
  ▼
  save AgentState
  │   stateStore.save(userId, sessionId, "agent_state", state)
  │
  ▼
  return result
```

This wiring lives in `ReActAgent` itself; `HarnessAgent` inherits it for free. The agent instance holds no fixed session — each call reads / writes the slot named by its `RuntimeContext` (falling back to the builder-time `defaultSessionId`).

> Mid-`call()` state changes happen against the in-memory `AgentState`. **The state store is written once per call (and on shutdown), not on every message** — so the throughput pressure on your backend stays low.

### Built-in and extension implementations

Anything implementing `io.agentscope.core.state.AgentStateStore` works. Pick by deployment shape:

| Implementation | Module | Use case |
|---|---|---|
| `InMemoryAgentStateStore` | `agentscope-core` | Unit tests / single-process demos; lost on exit |
| `JsonFileAgentStateStore` | `agentscope-core` | Local dev with file persistence; not cross-node. **`HarnessAgent` default**, rooted at `~/.agentscope/state/<agentId>/` (override the base via the `agentscope.state.home` system property); **single-host** |
| `RedisAgentStateStore` | `agentscope-extensions-session-redis` | **Production default** for multi-replica deployments; supports Jedis / Lettuce / Redisson (Standalone / Cluster / Sentinel) |
| `MysqlAgentStateStore` | `agentscope-extensions-session-mysql` | When state needs to flow into a relational store (audit, reporting) |

Switching is one call at builder time:

```java
// Default (single host) — omit .stateStore(...); a local JsonFileAgentStateStore is used automatically
HarnessAgent agent = HarnessAgent.builder()
    .name("MyAgent")
    .model(model)
    .workspace(workspace)
    .build();

// Production multi-replica — swap in RedisAgentStateStore
RedisClient client = RedisClient.create("redis://redis.prod:6379");
HarnessAgent agent = HarnessAgent.builder()
    .name("MyAgent")
    .model(model)
    .workspace(workspace)
    .stateStore(RedisAgentStateStore.builder().lettuceClient(client).build())
    .build();
```

:::{warning}
The built-in `JsonFileAgentStateStore` / `InMemoryAgentStateStore` are single-host only. If you've already chosen `filesystem(SandboxFilesystemSpec)` or `filesystem(RemoteFilesystemSpec)` (distributed workspace), HarnessAgent **rejects** a local state store at build time with `IllegalStateException` — sandbox state must be shared across replicas. Configure a distributed `AgentStateStore` (e.g. `RedisAgentStateStore`) via `.stateStore(...)`.
:::

### Real-time resume across processes and machines

Once the state store is distributed (e.g. Redis), cross-machine resume is **automatic**:

```java
// Node A — start a conversation
HarnessAgent agentA = HarnessAgent.builder()
    .stateStore(redisStore)
    /* ... */ .build();
agentA.call(msg, RuntimeContext.builder()
    .sessionId("alice-2026-06-02-001")
    .userId("alice")
    .build()).block();

// Node B — different physical machine, separate JVM
HarnessAgent agentB = HarnessAgent.builder()
    .stateStore(redisStore)
    /* same store backend */ .build();

// Node B's first call() with the same (userId, sessionId) loads the AgentState node A left in Redis
agentB.call(nextMsg, RuntimeContext.builder()
    .sessionId("alice-2026-06-02-001")
    .userId("alice")
    .build()).block();
```

This buys you:

- **Failover**: a crashed node — conversations migrate to a healthy one, user notices nothing.
- **Rolling deploys**: old pods save on shutdown, new pods load on first call — **conversations never break across releases**.
- **Cross-surface continuity**: a user starts in the Web UI, switches to the CLI — same `(userId, sessionId)`, all memory present.

The `(userId, sessionId)` pair defines the namespacing: `sessionId` alone is enough for most cases; add `userId` when you need per-user partitioning.

### Multi-user isolation

`sessionId` and `userId` solve different problems:

- **`sessionId`** — which conversation this is; independent `AgentState` snapshot.
- **`userId`** — which user owns this conversation; also drives which user's namespace files land in, see [Filesystem](./filesystem).

```java
agent.call(msg, RuntimeContext.builder()
    .sessionId("alice-1").userId("alice").build()).block();

agent.call(msg, RuntimeContext.builder()
    .sessionId("bob-1").userId("bob").build()).block();
```

Two users — separate state, separate filesystem paths, no crosstalk. For `AgentState`-level user isolation in production, set `userId` on the `RuntimeContext`: the store addresses each slot by `(userId, sessionId)` (with `RedisAgentStateStore` the `userId` becomes part of the Redis key) rather than relying on filesystem path bucketing.

### Reading and writing `AgentState` directly

When you need to bypass the agent loop (admin console, audit, batch migration):

```java
import io.agentscope.core.state.AgentState;

AgentState state = agent.getAgentState("alice", "session-001");
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
The 1.0 `Memory` interface (`InMemoryMemory` / `LongTermMemory`, etc.) is `@Deprecated(forRemoval = true)` in 2.0. New code should use `AgentState.getContext()` + an `AgentStateStore`; `Memory` remains only as a source-compat shim.
:::

### Letting the agent inspect its own history

When session capability is on (the default), three query tools are registered automatically:

- `session_list agentId="..."` — list an agent's historical sessions.
- `session_history agentId="..." sessionId="..." lastN=20` — recent N messages of a session.
- `session_search query="..." agentId="..."` — keyword search across history.

These tools read the **uncompressed conversation log** (`<workspace>/agents/<agentId>/sessions/<sessionId>.log.jsonl`), so even when the in-context conversation has been summarized, the agent can still pull up the original messages.

### Per-session interrupt

Each `AgentState` carries a transient `InterruptControl` (`io.agentscope.core.interruption.InterruptControl`) — a per-session interrupt signal that is **never serialised** to the state store (marked `@JsonIgnore transient` on `AgentState`). This allows targeted interruption of a single session's in-flight call without affecting other concurrent calls on the same agent instance.

```java
// Interrupt a specific session — only that session's call observes the signal
agent.interrupt("alice", "session-001");

// Interrupt with an injected user message
agent.interrupt("alice", "session-001", Msg.userMsg("Please stop and summarise."));
```

The reasoning loop checks `state.interruptControl().isInterrupted()` before each iteration. When triggered, the loop enters the `handleInterrupt` path, which saves state and returns the partial result.

The legacy no-arg `interrupt()` still works for single-session scenarios — it routes to the currently active session's `InterruptControl`.

:::{note}
`InterruptControl` is a runtime-only signal; it is never persisted. If a session resumes on a different node after failover, the interrupt flag starts cleared. The separate `AgentState.shutdownInterrupted` flag (which **is** persisted) records whether the session was interrupted by graceful shutdown — the agent can detect and recover from that on next load.
:::

### Concurrent usage

Because the agent is a stateless engine, a single instance handles concurrent requests naturally:

```java
HarnessAgent agent = HarnessAgent.builder()
    .name("SharedAssistant")
    .model(model)
    .workspace(workspace)
    .stateStore(redisStore)
    .build();

// Different users — fully parallel, no contention
Mono<Msg> aliceCall = agent.call(aliceMsg, RuntimeContext.builder()
    .userId("alice").sessionId("s1").build());
Mono<Msg> bobCall = agent.call(bobMsg, RuntimeContext.builder()
    .userId("bob").sessionId("s2").build());

Mono.zip(aliceCall, bobCall).block();  // both run in parallel

// Same user, same session — automatically serialised
Mono<Msg> call1 = agent.call(msg1, RuntimeContext.builder()
    .userId("alice").sessionId("s1").build());
Mono<Msg> call2 = agent.call(msg2, RuntimeContext.builder()
    .userId("alice").sessionId("s1").build());

// call2 queues behind call1 — conversation history stays consistent
Flux.merge(call1, call2).collectList().block();
```

**Concurrency rules:**
- **Different `(userId, sessionId)`** → fully parallel, each call works on its own `AgentState`.
- **Same `(userId, sessionId)`** → per-session async gate serialises calls in FIFO order — state consistency guaranteed without external locking.
- **`interrupt(userId, sessionId)`** → targets exactly one session, other in-flight calls unaffected.

:::{tip}
The in-memory state cache grows with the number of distinct sessions a single agent instance has served. For most deployments (hundreds of sessions) this is negligible. For very large-scale scenarios (millions of sessions per process), consider an agent factory pattern with bounded instance pools — but this is rarely needed since `AgentState` objects are lightweight.
:::

---

## Part 2: Context compaction strategies

The model's token budget is finite. A long-running conversation either compacts proactively or eventually crashes into the model's hard limit. `HarnessAgent` ships a full compaction stack — opt-in via `.compaction(...)` / `.toolResultEviction(...)`.

**Core invariant**: **compaction mutates `AgentState` in memory; the state store writes the updated `AgentState` at end of call**. Compaction and persistence are independent paths that always run in that order — the state store sees the post-compaction state.

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

`RuntimeContext` (in `io.agentscope.core.agent`) is a lightweight per-call carrier passed to `agent.call(msgs, ctx)`; hooks and tools share it for the duration of one call. Its free-form / typed attributes are **not persisted**; its `sessionId` / `userId` fields select which `AgentState` slot the state store loads and saves for this call. At call entry, the framework injects the call-scoped `AgentState` onto the `RuntimeContext` so that middleware, tools, and hooks can access the correct per-call state via `ctx.getAgentState()`.

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
| `getSessionId()` / `getUserId()` | Built-in fields used to route the state slot and tenant |
| `getAgentState()` / `setAgentState(AgentState)` | Call-scoped `AgentState`, injected by the framework at call entry. Middleware and tools should read state from here, not from `agent.getAgentState()` |
| `resolveAgentState(ctx, agent)` | Static helper: returns `ctx.getAgentState()` if available, falls back to `agent.getAgentState()`. Use this in middleware/tools for concurrency safety |
| `get(String)` / `put(String, Object)` | String-keyed get/put |
| `get(Class<T>)` / `put(Class<T>, T)` | Typed singleton get/put |
| `getExtra()` | Direct access to the string-attribute map (mutable view) |
| `RuntimeContext.empty()` | Empty context |

:::{tip}
**The `AgentStateStore` backend is bound at builder time and cannot be switched per call via `RuntimeContext`.** What *does* vary per call is the `(userId, sessionId)` slot it addresses — set `userId` for per-user isolation (or a custom `keyPrefix` on the store); do not try to hand each call a different state store instance.
:::

:::{tip}
**Accessing `AgentState` from middleware and tools:** Always use `RuntimeContext.resolveAgentState(ctx, agent)` rather than `agent.getAgentState()` during call execution. Under concurrency, `agent.getAgentState()` returns the last-active session's state (an arbitrary choice when multiple calls are in flight), while `ctx.getAgentState()` returns the state for **this call's** session — which is what you almost always want.
:::

---

## Related pages

- [Architecture](./architecture) — how Context, state persistence, and workspace cooperate inside one call
- [Memory](./memory) — long-term memory, full compaction configuration, large tool-result offloading, background maintenance
- [Plan Mode](./plan-mode) — independent persistence and recovery of plan state
- [Subagent](./subagent) — where background tasks live and how they survive node migration
- [Filesystem](./filesystem) — `userId`-based multi-tenant path isolation
- [Permissions](../building-blocks/permission-system.md) — persistence of permission rules
