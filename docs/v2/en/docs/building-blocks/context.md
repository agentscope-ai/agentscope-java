---
title: Context & AgentState
description: Stateless agent engine, AgentState lifecycle, state persistence, and
  RuntimeContext
zh_link: /v2/zh/docs/building-blocks/context
---

## How this page relates to Harness context construction

This page explains **where session state lives, how it is persisted and restored, and how tools
access the current call's state**. For model message layout, business sources, budgets and
prompt examples, see [Harness context construction](/v2/en/docs/harness/context).

| Concept | Responsibility | Sent directly to the model? |
| --- | --- | --- |
| AgentState | Conversation history, task state, plan mode and permissions | Not as a whole; request construction selects content |
| RuntimeContext | Current call identity, attributes and AgentState reference | Arbitrary attributes are not automatically prompt content |
| Model request context | Instructions, messages, state projections, references and tool schemas for one inference | Yes; not a raw serialization of persisted state |

Harness projects task state into transient TASK_STATE and loads MEMORY.md as reference data.
These generated messages are not thereby appended to durable history. Plain ReActAgent does
not automatically enable the Harness material and budget policies.

## Stateless Agent Engine

`ReActAgent` (and `HarnessAgent` that wraps it) is designed as a **stateless engine**: the instance reuses configuration — system prompt, model, tools and middleware — while recoverable session data lives in `AgentState`; the instance also holds runtime facilities such as state caches and execution gates, indexed by `(userId, sessionId)`. A single agent instance can concurrently serve many users and sessions; the caller simply passes a different `RuntimeContext` on each `call()`.

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
- **Session concurrency.** Within one instance, different identity pairs may run in parallel; the same slot is serialized by its execution gate. This is not a distributed lock. Shared tools, middleware and business dependencies must be thread-safe.
- **Automatic persistence.** With a state store configured, the framework loads and saves state. Ordinary chat needs no manual state management, but business objectives, requirement decisions and evidence versions still require explicit application updates.
- **Call-scoped access.** Tools and middleware use the framework-injected RuntimeContext.getAgentState() for the current session. Do not keep a global current-state reference. This API is not authorization or isolation for externally shared objects.

---

## AgentState

An [`AgentStateStore`](/v2/en/integration/session/index) persists an **`AgentState`** (`io.agentscope.core.state.AgentState`) — a complete snapshot of everything that makes the agent restartable:

| `AgentState` field | Content |
|---|---|
| `getSessionId()` | The session identifier this state belongs to |
| `getUserId()` | The user identifier (nullable for anonymous sessions) |
| `getContext()` / `contextMutable()` | Current conversation history (user / assistant / tool calls / tool results) |
| `getSummary()` | Compacted summary (when compaction is enabled) |
| `getPermissionContext()` | Tool permission rules — see [Permissions](/v2/en/docs/building-blocks/permission-system) |
| `getPlanModeContext()` | Whether Plan Mode is active, current plan file path |
| `getTasksContext()` | Todo, revision, and optional objective, requirements, evidence-binding versions and verification summaries |
| `getToolContext()` | Active toolkit groups (`activatedGroups`) |

Execution controls are separate from `AgentState`: each invocation owns an independent interrupt signal. See [Per-session interrupt](#per-session-interrupt) below.

At the end of each `call()`, the framework writes the entire `AgentState` to the state store under the key `agent_state`, addressed by the call's `(userId, sessionId)`. The next `call()` with the same `(userId, sessionId)` loads it back automatically. A shared store lets other instances load successfully persisted state. It does not guarantee real-time consistency between concurrent calls or recover unsaved progress and external side effects.

### Task state is not automatic business acceptance

Todo is updated by todo_write or the application; the application sets task identity and objective
through beginTask. The optional proposal tool only creates candidates; a trusted caller confirms
or rejects through decide. The application maintains subject versions, and explicit
VerificationService calls produce verification summaries.

Persisting fields does not automatically infer them from user messages, PLAN.md or tool text.
Progress completion, confirmed requirements and passed checks have different meanings; none
automatically establishes overall completion. Harness taskContext options control projection.
See [Optional task information](/v2/en/docs/harness/context#optional-task-information)
for the field/writer mapping and display configuration.

### The auto-persistence and recovery flow

```
call(msgs, RuntimeContext(userId, sessionId))
  │
  ├─ per-session gate: serialise same (uid, sid), others run in parallel
  │
  ▼
  reload from store on each call if configured; otherwise use slot cache
  │   inject onto RuntimeContext: rc.setAgentState(state)
  │
  ▼
  reasoning loop
  │   messages update context; Plan, Todo and permissions update their own substate
  │   Harness builds transient model views; validates before committing compacted history
  │
  ▼
  save AgentState
  │   stateStore.save(userId, sessionId, "agent_state", state)
  │
  ▼
  return result
```

This wiring lives in `ReActAgent` itself; `HarnessAgent` inherits it for free. The agent instance holds no fixed session — each call reads / writes the slot named by its `RuntimeContext` (falling back to the builder-time `defaultSessionId`).

> Reasoning mainly updates in-memory state. Normal completion, controlled failure/interruption and shutdown paths attempt to save agent_state; forced process termination may prevent saving. Administrative operations such as clearContext can also save explicitly. Execution observations and verification reports write separate state keys, so the store does not necessarily receive only one write per call.

### Built-in and extension implementations

Anything implementing `io.agentscope.core.state.AgentStateStore` works. Pick by deployment shape:

| Implementation | Module | Use case |
|---|---|---|
| `InMemoryAgentStateStore` | `agentscope-core` | Unit tests / single-process demos; lost on exit |
| `JsonFileAgentStateStore` | `agentscope-core` | Local dev with file persistence; not cross-node. **`HarnessAgent` default**, rooted at `~/.agentscope/state/<agentId>/` (override the base via the `agentscope.state.home` system property); **single-host** |
| `RedisAgentStateStore` | `agentscope-extensions-redis` | **Production default** for multi-replica deployments; supports Jedis / Lettuce / Redisson (Standalone / Cluster / Sentinel) |
| `MysqlAgentStateStore` | `agentscope-extensions-mysql` | When state needs to flow into a relational store (audit, reporting) |

Switching is one call at builder time:

```java
// Default (single host) — omit .stateStore(...); a local JsonFileAgentStateStore is used automatically
HarnessAgent agent = HarnessAgent.builder()
    .name("MyAgent")
    .model(model)
    .workspace(workspace)
    .build();

// Production multi-replica — use DistributedStore
JedisPooled jedis = new JedisPooled("redis://redis.prod:6379");
HarnessAgent agent = HarnessAgent.builder()
        .name("MyAgent")
        .model(model)
        .workspace(workspace)
        .stateStore(new RedisAgentStateStore(jedis))
        .distributedStore(RedisDistributedStore.fromJedis(jedis))
        .build();
```


<Warning>

The built-in `JsonFileAgentStateStore` / `InMemoryAgentStateStore` are single-host only. If you've already chosen `filesystem(SandboxFilesystemSpec)` or `filesystem(RemoteFilesystemSpec)` (distributed workspace), HarnessAgent **rejects** a local state store at build time with `IllegalStateException` — sandbox state must be shared across replicas. Configure a distributed store via `.distributedStore(...)` (e.g. `RedisDistributedStore`) or `.stateStore(...)`.

</Warning>


### Resume saved sessions across processes and machines

With a shared store, each call reloads the slot's persisted state. This example assumes node A has finished saving before node B continues, not concurrent execution of the same session:

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
    /* same state store */ .build();

// Node B's first call() with the same (userId, sessionId) loads the AgentState node A left in Redis
agentB.call(nextMsg, RuntimeContext.builder()
    .sessionId("alice-2026-06-02-001")
    .userId("alice")
    .build()).block();
```

This buys you:

- **Recovery**: another node can load the last successfully saved snapshot. Unsaved progress may be lost; reconcile external tool effects before replaying work.
- **Rolling deploys**: graceful shutdown attempts to save, and a new instance loads persisted state. Allow shutdown time and verify that state formats and business configuration remain suitable.
- **Cross-surface continuity**: Web UI and CLI can continue saved sessions with the same store and identity slot; the application must authorize access.

The identity pair selects the state slot. Anonymous/single-tenant calls may omit userId; multi-tenant applications must obtain it from trusted authentication, not arbitrary client input.

Cross-instance concurrency also needs session routing or distributed coordination. Versioned stores use CAS on save, with ConflictPolicy determining conflict handling; unversioned stores lack this protection. CAS does not undo external tool side effects.

### Multi-user isolation

`sessionId` and `userId` solve different problems:

- **`sessionId`** — which conversation this is; independent `AgentState` snapshot.
- **`userId`** — which user owns this conversation; also drives which user's namespace files land in, see [Filesystem](/v2/en/docs/harness/filesystem).

```java
agent.call(msg, RuntimeContext.builder()
    .sessionId("alice-1").userId("alice").build()).block();

agent.call(msg, RuntimeContext.builder()
    .sessionId("bob-1").userId("bob").build()).block();
```

Different identity pairs select different state slots; filesystem sharing separately depends on IsolationScope. Set RuntimeContext.userId after authentication and authorization: the store addresses each slot by `(userId, sessionId)` (with `RedisAgentStateStore` the `userId` becomes part of the Redis key) rather than relying on filesystem path bucketing.

### Reading and writing `AgentState` directly

For out-of-loop reads such as administration or auditing, address state by identity. Do not mutate it concurrently with an active call. Obtaining or changing the object does not itself persist it or provide a transaction or authorization check:

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

### Clearing a session's conversation context

To let a user start a fresh topic without creating a new session, call `clearContext`. It keeps the
same `(userId, sessionId)` and preserves non-conversation state such as permissions, tools, tasks,
and Plan Mode. It clears the historical message buffer and compaction summary, then immediately
persists the result when the agent has an `AgentStateStore`.

```java
agent.clearContext("alice", "session-001");

// Or use the same RuntimeContext used by calls.
agent.clearContext(RuntimeContext.builder()
    .userId("alice")
    .sessionId("session-001")
    .build());
```

Call it after the session's current request has completed; it does not cancel an in-flight call.
The next request can still load System, workspace references and retained task/plan projections.
It is not a complete model-input reset. For a fresh session, use a new sessionId rather than merely clearing history.


<Note>

The 1.0 `Memory` interface (`InMemoryMemory` / `LongTermMemory`, etc.) is `@Deprecated(forRemoval = true)` in 2.0. New code should use `AgentState.getContext()` + an `AgentStateStore`; `Memory` remains only as a source-compat shim.

</Note>


### Per-session interrupt

Each execution owns a runtime-only `InterruptControl`. It is neither stored on `AgentState` nor persisted with conversation history. A session-targeted interrupt resolves the currently admitted execution:

```java
agent.interrupt("alice", "session-001");
agent.interrupt("alice", "session-001", new UserMessage("Please stop."));
```

An idle session is unaffected. To select a particular queued or running invocation, use the `AgentRun` returned by `prepareRun` or `prepareCall`; see [execution control](/v2/en/docs/building-blocks/agent#control-one-execution). Queued B and running A have independent controls even when they share a session.

The reasoning loop checks its execution's signal at cooperative checkpoints. A user interrupt produces an interrupted recovery reply and saves conversation state. The deprecated no-argument `interrupt()` targets the default session's current execution, never the most recently used context.

`AgentState.shutdownInterrupted` is a separate, persisted recovery marker. Graceful shutdown binds both the execution control and the state resolved for that call; queued calls have no state to save. No interrupt flag is carried into the next run or loaded on another node.

### Concurrent usage

One instance can handle concurrent sessions; shared tools and middleware in this example must be thread-safe:

```java
HarnessAgent agent = HarnessAgent.builder()
    .name("SharedAssistant")
    .model(model)
    .workspace(workspace)
    .stateStore(redisStore)
    .build();

// Different user slots can run concurrently; shared business dependencies may contend
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

// Same-slot calls execute in gate-entry order; variable declaration order does not guarantee subscription order
Flux.merge(call1, call2).collectList().block();
```

**Concurrency rules:**
- **Different identity pairs** → may run concurrently with different session states; shared business resources still need coordination.
- **Same instance and identity pair** → serialized in execution-gate entry order; cross-instance access needs separate coordination.
- **`interrupt(userId, sessionId)`** → targets exactly one session, other in-flight calls unaffected.


<Tip>

The in-memory state cache grows with the number of distinct sessions a single agent instance has served. For most deployments (hundreds of sessions) this is negligible. For very large-scale scenarios (millions of sessions per process), consider an agent factory pattern with bounded instance pools — but this is rarely needed since `AgentState` objects are lightweight.

</Tip>


---

## `RuntimeContext` — per-call metadata

`RuntimeContext` (in `io.agentscope.core.agent`) is a lightweight per-call carrier passed to `agent.call(msgs, ctx)`; hooks and tools share it for the duration of one call. Its free-form / typed attributes are **not automatically persisted or included in model messages**; its `sessionId` / `userId` fields select which `AgentState` slot the state store loads and saves for this call. At call entry, the framework injects the call-scoped `AgentState` onto the `RuntimeContext` so that middleware, tools, and hooks can access the correct per-call state via `ctx.getAgentState()`.

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
| `resolveAgentState(ctx, agent)` | Static helper: returns `ctx.getAgentState()` if available, falls back to `agent.getAgentState()`. Ensure the current call has injected its state; fallback does not guarantee the intended business session |
| `get(String)` / `put(String, Object)` | String-keyed get/put |
| `get(Class<T>)` / `put(Class<T>, T)` | Typed singleton get/put |
| `getExtra()` | Direct access to the string-attribute map (mutable view) |
| `RuntimeContext.empty()` | Empty context |


<Tip>

**The `AgentStateStore` is bound at builder time and cannot be switched per call via `RuntimeContext`.** What *does* vary per call is the `(userId, sessionId)` slot it addresses — set `userId` for per-user isolation (or a custom `keyPrefix` on the store); do not try to hand each call a different state store instance.

</Tip>



<Tip>

**Accessing `AgentState` from middleware and tools:** Always use `RuntimeContext.resolveAgentState(ctx, agent)` rather than `agent.getAgentState()` during call execution. The deprecated no-argument agent.getAgentState() returns the anonymous default-session slot, not the last active session. ctx.getAgentState() is the current call's state. resolveAgentState still falls back when it is absent; fallback is not a session-routing guarantee.

</Tip>


---

## Related pages

- [Harness context construction](/v2/en/docs/harness/context) — final message layout, dynamic sources, task projections and budgets

- [Agent](/v2/en/docs/building-blocks/agent) — full `ReActAgent` API and builder fields
- [Context Compaction](/v2/en/docs/harness/compaction) — conversation summarization, tool-result eviction, overflow recovery (builds on top of the AgentState foundation described here)
- [Memory](/v2/en/docs/harness/memory) — long-term memory, background maintenance
- [Permissions](/v2/en/docs/building-blocks/permission-system) — persistence of permission rules
