---
title: "Context"
description: "Manage the agent's working memory and session state"
---

## Overview

Context is the agent's working memory — every message the LLM sees during reasoning (user input, assistant replies, tool calls, tool results). AgentScope Java keeps long-running agents on track through three mechanisms:

- **`AgentState.getContext()`** — the agent's conversation history, stored as a message list; the framework appends every new turn automatically.
- **`Session` + `SessionKey`** — persists the entire `AgentState` to external storage (built-in `InMemorySession`, `JsonSession`), so the agent can resume across processes.
- **`RuntimeContext`** — per-call metadata: `userId`, `sessionId`, plus arbitrary string-keyed or typed attributes; hooks and tools share the same context for the duration of one call. **Not persisted.**

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

:::{note}
Python's `ContextConfig`-driven automatic compaction and the `Offloader` interface for tool-result offloading are not yet available in Java 2.0. `maxIters` and `reactConfig` provide basic loop bounds to keep long-running tasks from growing unbounded.
:::

## Passing per-call metadata with RuntimeContext

`RuntimeContext` (`io.agentscope.core.agent`) is a lightweight metadata container passed to `agent.call(msgs, runtimeContext)` and shared across hooks and tools during one call.

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
| `get(String)` / `put(String, Object)` | String-keyed access (legacy style) |
| `get(Class<T>)` / `put(Class<T>, T)` | Typed singleton access |
| `get(String, Class<T>)` / `put(String, Class<T>, T)` | Both key + type |
| `getExtra()` | The string-keyed map (mutable view) |
| `RuntimeContext.empty()` | Empty context (no persistence, all fields blank) |

`RuntimeContext` does not participate in persistence — for cross-call data, use `Session` below.

## Persisting conversation history with Session

The `Session` interface (`io.agentscope.core.session`) abstracts state storage, partitioned by `SessionKey`. Built-ins:

| Implementation | Description |
|----------------|-------------|
| `InMemorySession` | Process-local map; useful for unit tests |
| `JsonSession` | JSON file persistence, partitioned by key |

When you configure both on the builder, the agent persists `AgentState` (including `context`, `tasksContext`, `permissionContext`, …) after every `call`; on startup, if data exists for the given key, it's loaded automatically.

```java
import io.agentscope.core.ReActAgent;
import io.agentscope.core.formatter.dashscope.DashScopeChatFormatter;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.UserMessage;
import io.agentscope.core.model.DashScopeChatModel;
import io.agentscope.core.session.JsonSession;
import io.agentscope.core.session.Session;
import io.agentscope.core.state.SimpleSessionKey;
import io.agentscope.core.tool.Toolkit;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;

Path sessionDir = Paths.get(System.getProperty("user.home"), ".agentscope", "sessions");
Session session = new JsonSession(sessionDir);

ReActAgent agent =
        ReActAgent.builder()
                .name("my_agent")
                .sysPrompt("...")
                .model(
                        DashScopeChatModel.builder()
                                .apiKey(System.getenv("DASHSCOPE_API_KEY"))
                                .modelName("qwen-max")
                                .stream(true)
                                .formatter(new DashScopeChatFormatter())
                                .build())
                .toolkit(new Toolkit())
                .session(session)
                .sessionKey(SimpleSessionKey.of("user-alice:demo"))
                .build();

// Existing data for this key is auto-loaded into AgentState.
int loaded = agent.getState().getContext().size();
System.out.println("loaded " + loaded + " message(s)");

Msg result = agent.call(List.of(new UserMessage("Resume the previous task."))).block();
// Auto-persisted on completion.
```

`SimpleSessionKey` accepts a single ID string. For multi-dimensional partitioning like `(userId, agentId, sessionId)`, implement `SessionKey` yourself (see `SessionKey.java` javadoc). Runnable examples: `agentscope-examples/documentation/.../session/SessionExample.java`, `session/SessionAutoSaveExample.java`, `context/RuntimeContextExample.java`.

## Reading and writing AgentState directly

`AgentState` (`io.agentscope.core.state`) holds everything the agent needs to resume. Common entry points:

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

## Further reading

::::{grid} 2

:::{grid-item-card} Agent
:link: /v2/building-blocks/agent

The ReAct loop and how context flows between reasoning steps
:::
  :::{grid-item-card} Middleware
:link: /v2/building-blocks/middleware

Hook into model calls and system prompt assembly
:::
  :::{grid-item-card} Tool
:link: /v2/building-blocks/tool

Tool calls and tool results that flow into context
:::
  :::{grid-item-card} Permission System
:link: /v2/building-blocks/permission-system

Runtime rules stored in AgentState.permissionContext
:::

::::
