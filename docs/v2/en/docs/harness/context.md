---
title: "Context & Session"
description: "Manage the agent's working memory across calls, processes, and users"
---

Context is the agent's working memory — every message the LLM sees during reasoning (user input, assistant replies, tool calls, tool results). AgentScope Java keeps long-running agents on track through three mechanisms:

- **`AgentState.getContext()`** — the agent's conversation history, stored as a message list; the framework appends every new turn automatically.
- **`Session` + `SessionKey`** — persists the entire `AgentState` to external storage (built-in `InMemorySession`, `JsonSession`), so the agent can resume across processes.
- **`RuntimeContext`** — per-call metadata: `userId`, `sessionId`, plus arbitrary string-keyed or typed attributes; hooks and tools share the same context for the duration of one call. **Not persisted.**

## How the model API input is assembled

Before each model call, the agent assembles the API input as follows:

```text
Model API Input/
├── Base system prompt (sysPrompt on the builder)
├── Skill instructions (from Toolkit)
└── onSystemPrompt middleware transforms
```

How each layer is built:

1. **System prompt** — starts from the `sysPrompt` set on the builder, appends skill instructions (each skill's name and description, from the toolkit), then runs every `onSystemPrompt` [middleware](/v2/building-blocks/middleware) hook in sequence.
2. **Context** — the message list held by `AgentState.getContext()` (user inputs, assistant replies, tool calls, tool results).

:::{tip}
Use the `onSystemPrompt` middleware hook to inject dynamic context — working-directory hints, time-sensitive info, environment details — without rewriting the base prompt. See `agentscope-examples/documentation/.../middleware/SystemPromptMiddlewareExample.java` for a complete example.
:::

## RuntimeContext: per-call metadata

`RuntimeContext` (`io.agentscope.core.agent`) is a lightweight container passed to `agent.call(msgs, runtimeContext)` and shared across hooks and tools during one call.

```java
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.UserMessage;
import java.util.List;

RuntimeContext ctx =
        RuntimeContext.builder()
                .userId("alice")
                .sessionId("s-001")
                .put("request_id", "req-2026-06-01-abc")
                .put(MyTenantInfo.class, new MyTenantInfo("tenant-7"))
                .build();

Msg result = agent.call(List.of(new UserMessage("Hi")), ctx).block();

// Read it:
String reqId = ctx.get("request_id");
MyTenantInfo info = ctx.get(MyTenantInfo.class);
```

Available access methods:

| Method | Description |
|--------|-------------|
| `getSessionId()` / `getUserId()` / `getSession()` / `getSessionKey()` | Built-in fields |
| `get(String)` / `put(String, Object)` | String-keyed access |
| `get(Class<T>)` / `put(Class<T>, T)` | Typed singleton access |
| `get(String, Class<T>)` / `put(String, Class<T>, T)` | Both key + type |
| `getExtra()` | The string-keyed map (mutable view) |
| `RuntimeContext.empty()` | Empty context (no persistence, all fields blank) |

`RuntimeContext` does not participate in persistence — for cross-call data, use `Session`.

## Session: cross-process persistence

Pass a stable `sessionId` in each `call()` and the agent will continue the previous conversation across restarts, node failovers, and different processes. Persistence is **on by default**.

```java
RuntimeContext ctx = RuntimeContext.builder()
    .sessionId("alice-2026-06-02-001")
    .userId("alice")
    .build();

agent.call(msg,  ctx).block();   // first call: starts fresh
agent.call(msg2, ctx).block();   // second call (same or different process): resumes
```

### What each `call()` writes

Each `call()` produces two output tracks automatically:

1. **Runtime snapshot** — the agent's full recoverable state (conversation context, permission rules, Plan Mode state, tool state, etc.) serialized into `agents/<agentId>/context/<sessionId>/`. The next `call()` with the same `sessionId` loads it back.
2. **Full conversation log** — never-compacted, append-only `agents/<agentId>/sessions/<sessionId>.log.jsonl`, for audit and `session_search`.

Two independent paths; they don't overwrite each other.

### Default layout

```
workspace/agents/<agentId>/
├── context/<sessionId>/         runtime snapshot (auto-written)
│   ├── agent.json
│   └── *.json
└── sessions/
    ├── sessions.json            session index
    ├── <sessionId>.jsonl        LLM-visible compacted context
    └── <sessionId>.log.jsonl    full conversation log (never compacted)
```

If you don't pass any `session(...)` config, the framework uses this workspace-based layout.

### Configuring a Session

Built-in implementations:

| Implementation | Description |
|---|---|
| `InMemorySession` | Process-local map; useful for unit tests |
| `JsonSession` | JSON file persistence, partitioned by key |

Just want a different directory:

```java
HarnessAgent.builder()
    .session(new JsonSession(Path.of("/custom/sessions")))
    .build();
```

Need distributed (shared across pods) — swap to a Redis-backed Session:

```java
HarnessAgent.builder()
    .session(myRedisSession)
    .build();
```

Override per-call:

```java
agent.call(msg, RuntimeContext.builder()
    .sessionId("sess-001")
    .session(customSession)
    .build()).block();
```

For non-`HarnessAgent` use, you can also configure on `ReActAgent.Builder` with `.session(session).sessionKey(SimpleSessionKey.of("user-alice:demo"))`. `SimpleSessionKey` accepts a single ID string. For multi-dimensional partitioning like `(userId, agentId, sessionId)`, implement `SessionKey` yourself.

### Multi-user isolation

`sessionId` and `userId` solve different things:

- **`sessionId`** — which conversation is which; independent runtime snapshots
- **`userId`** — which user's namespace files land under (see [Filesystem · multi-user isolation](./filesystem))

```java
agent.call(msg, RuntimeContext.builder()
    .sessionId("alice-1").userId("alice").build()).block();

agent.call(msg, RuntimeContext.builder()
    .sessionId("bob-1").userId("bob").build()).block();
```

The two users' conversation state and file paths don't interfere.

:::{warning}
**Known limitation**: the default workspace-based Session does not auto-bucket the runtime snapshot directory by `userId`. For multi-tenant deployments that need user-level `AgentState` isolation, use a Session implementation with distributed keys (e.g. Redis) that encodes `userId` into the key, or supply a custom namespace strategy.
:::

### Disabling auto persistence

`disableSessionPersistence()` is a **no-op** in 2.0. To actually disable persistence: don't pass `session(...)` at build time, or don't pass `sessionId` in any `call()` — without a sessionId, the framework has nowhere to write.

### Tools the agent uses to browse history

When session is enabled (the default), three query tools are auto-registered:

- `session_list agentId="..."` — list past sessions of an agent
- `session_history agentId="..." sessionId="..." lastN=20` — last N messages of a session
- `session_search query="..." agentId="..."` — keyword search across past sessions

## Reading and writing AgentState directly

`AgentState` (`io.agentscope.core.state`) holds everything the agent needs to resume.

| Method | Description |
|--------|-------------|
| `getContext()` | Current conversation history (immutable view) |
| `contextMutable()` | Mutable view, use with care |
| `setSummary(...)` / `getSummary()` | Custom compaction summary (if you implement your own compaction middleware) |
| `getPermissionContext()` | Permission context, see [Permission System](/v2/building-blocks/permission-system) |
| `getTasksContext()` | The task list maintained by `TodoTools` |
| `toJson()` / `fromJsonString(String)` | JSON serialisation |

```java
import io.agentscope.core.state.AgentState;

AgentState state = agent.getState();
System.out.println("messages: " + state.getContext().size());
String json = state.toJson();
AgentState restored = AgentState.fromJsonString(json);
```

:::{note}
The `Memory` interface from 1.0 (`InMemoryMemory` / `LongTermMemory` / …) is `@Deprecated(forRemoval = true)` in 2.0. New code should use `AgentState.getContext()` + `Session` instead — `Memory` is kept only as a source-compatibility shim.
:::

## Related pages

- [Architecture](./architecture) — how Context, Session, and the workspace cooperate during a call
- [Memory](./memory) — long-term memory, compaction, large-result offloading
- [Filesystem](./filesystem) — `userId` multi-tenant path isolation
- [Subagent](./subagent) — how subagent sessions are kept distinct from the parent's
