---
title: "Agent"
description: "Learn how to define and configure agents in AgentScope Java 2.0"
---

## Overview

`Agent` (interface at `io.agentscope.core.agent.Agent`, default implementation `ReActAgent`) is the core abstraction — a reasoning-acting loop engine that integrates models, tools, the permission system, human-in-the-loop, context management, middlewares, state management, and the event system into a single unified interface.

Its primary responsibilities are:

- Receive input messages or events; orchestrate tools to complete tasks.
- Manage context (conversation history is held on `AgentState.getContext()` and can be persisted automatically via `Session`).
- Provide middleware hooks at key lifecycle points for custom logic.
- Manage concurrent and sequential tool execution automatically.

### Core interface

The `Agent` interface composes three capability interfaces: `CallableAgent`, `StreamableAgent`, `ObservableAgent`. The most commonly used methods:

| Method | Description |
|--------|-------------|
| `call(List<Msg>)` / `call(List<Msg>, RuntimeContext)` | Run the reasoning-acting loop and return `Mono<Msg>` |
| `streamEvents(List<Msg>)` / `streamEvents(Msg)` | Same loop, but emits `AgentEvent`s incrementally |
| `observe(Msg)` / `observe(List<Msg>)` | Append messages to context without triggering reasoning (returns `Mono<Void>`) |

`ReActAgent` adds overloads for structured output (`call(msgs, structuredOutputClass, runtimeContext)`) and convenient per-call metadata via `RuntimeContext`.

### Main loop

Each `call` runs through the reasoning-acting loop. The diagram below shows the main control flow:

```{mermaid}
flowchart TD
    A([Input: messages / event]) --> B{Waiting on\nexternal event?}
    B -- yes --> C[Apply event\nupdate tool state]
    B -- no --> D[Append to context]
    C --> E
    D --> E

    E{Decide next action} -- exit --> F([Return: waiting on\nexternal interaction])
    E -- reason --> G[Compress context if needed]
    G --> H[LLM call]
    H -- no tool calls --> I([Return final message])
    H -- tool calls --> Acting

    subgraph Acting [Acting]
        direction TB
        J[Batch tool calls\nserial / concurrent] --> L[Execute tool calls]
        L --> M{Permission\ncheck}
        M -- allow --> N[Run tool → result]
        M -- ask / external --> O([Pause and emit\nRequireUserConfirmEvent])
        M -- deny --> P[Return error to LLM]
    end

    N --> E
    P --> E
```

## Configuring an agent

Build an agent with `ReActAgent.builder()...build()`. `.model(...)` takes either a `ModelRegistry`-resolved string id (most common — picks up env vars automatically) or an explicit `Model` instance (when you need explicit control over timeouts / custom endpoints / etc.).

::::{tab-set}
:::{tab-item} String model id (recommended)
```java
import io.agentscope.core.ReActAgent;
import io.agentscope.core.tool.Toolkit;

ReActAgent agent =
        ReActAgent.builder()
                .name("my_agent")
                .sysPrompt("You are a helpful assistant.")
                // Resolved by ModelRegistry; reads DASHSCOPE_API_KEY automatically.
                // Switch providers by using "openai:gpt-5.5" / "anthropic:claude-sonnet-4-5"
                // / "gemini:gemini-2.0-flash" / "ollama:llama3".
                .model("dashscope:qwen-plus")
                .toolkit(new Toolkit())
                .build();
```
:::
:::{tab-item} Explicit Model builder
```java
import io.agentscope.core.ReActAgent;
import io.agentscope.core.formatter.dashscope.DashScopeChatFormatter;
import io.agentscope.core.model.DashScopeChatModel;
import io.agentscope.core.tool.Toolkit;

ReActAgent agent =
        ReActAgent.builder()
                .name("my_agent")
                .sysPrompt("You are a helpful assistant.")
                .model(
                        DashScopeChatModel.builder()
                                .apiKey("YOUR_API_KEY")
                                .modelName("qwen-max")
                                .stream(true)
                                .formatter(new DashScopeChatFormatter())
                                .build())
                .toolkit(new Toolkit())
                .build();
```
:::
:::{tab-item} With Toolkit / MCP
```java
import io.agentscope.core.ReActAgent;
import io.agentscope.core.tool.Toolkit;
import io.agentscope.core.tool.builtin.TodoTools;
import io.agentscope.core.tool.mcp.McpClientBuilder;
import io.agentscope.core.tool.mcp.McpClientWrapper;

Toolkit toolkit = new Toolkit();
toolkit.registerTool(new TodoTools());          // reflectively register @Tool methods
toolkit.registerTool(new MyCustomTools());      // custom tool class

McpClientWrapper amap = McpClientBuilder.streamableHttp()
        .name("amap")
        .url("https://mcp.amap.com/mcp?key=" + System.getenv("AMAP_API_KEY"))
        .build();
toolkit.registerMcpClient(amap).block();

ReActAgent agent =
        ReActAgent.builder()
                .name("my_agent")
                .sysPrompt("You are a helpful assistant.")
                .model("dashscope:qwen-max")
                .toolkit(toolkit)
                .build();
```
:::
::::

:::{tip}
The `ModelRegistry` string form (`<provider>:<model>`) supports `dashscope` / `openai` / `anthropic` / `gemini` / `ollama` and reads the matching API key (`DASHSCOPE_API_KEY` / `OPENAI_API_KEY` / `ANTHROPIC_API_KEY` / `GEMINI_API_KEY`) from the environment. For long-running scenarios that also need a workspace, session persistence, memory compaction, subagents, and so on, use [`HarnessAgent`](../harness/architecture.md) — it is a thin wrapper around `ReActAgent` with a largely identical builder.
:::

### Builder fields

| Field | Type | Default | Description |
|-------|------|---------|-------------|
| `name` | `String` | required | Agent identifier, used for messages and logs |
| `sysPrompt` | `String` | required | The base system prompt |
| `model` | `Model` | required | The LLM driving reasoning (extends `ChatModelBase`) |
| `toolkit` | `Toolkit` | `new Toolkit()` | Manages tools, MCP clients, skills, and tool groups |
| `middlewares` | `List<? extends MiddlewareBase>` | `List.of()` | Applied to agent / reasoning / acting / model call / system prompt hooks |
| `session` + `sessionKey` | `Session` + `SessionKey` | `null` (no persistence) | When set, agent automatically loads/saves `AgentState` on every `call` |
| `permissionContext` | `PermissionContextState` | `DEFAULT` mode | Fine-grained tool execution rules, see [Permission System](./permission-system.md) |
| `modelConfig` | `ModelConfig` | default | Model retries and fallback model |
| `reactConfig` | `ReactConfig` | default | Max iterations and reject handling |
| `maxIters` | `int` | `10` | Max iterations of the ReAct main loop (alternative to `reactConfig`) |

## Running an agent

`call` and `streamEvents` accept the same input messages and drive the same reasoning-acting loop. They differ in how the result is delivered.

### call

`call` consumes all events internally and returns the final `Msg` when the agent finishes or pauses for external interaction.

```java
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.UserMessage;
import java.util.List;

UserMessage msg = new UserMessage("What files are in the current directory?");
Msg result = agent.call(List.of(msg), RuntimeContext.empty()).block();
System.out.println(result.getTextContent());
```

### streamEvents

`streamEvents` emits `AgentEvent`s one by one so you can stream text, tool-call progress, and lifecycle events to your UI in real time. Dispatch on `event.getType()` to handle each kind:

```java
import io.agentscope.core.event.AgentEventType;
import io.agentscope.core.event.TextBlockDeltaEvent;
import io.agentscope.core.event.ToolCallStartEvent;

agent.streamEvents(new UserMessage("Summarize the README."))
        .doOnNext(event -> {
            if (event.getType() == AgentEventType.TEXT_BLOCK_DELTA) {
                // Streaming text fragment — append to UI or stdout
                System.out.print(((TextBlockDeltaEvent) event).getDelta());
            } else if (event.getType() == AgentEventType.TOOL_CALL_START) {
                // The agent is about to call a tool — surface the call info
                System.out.println("\n[tool] " + ((ToolCallStartEvent) event).getToolName());
            }
            // Other events: thinking blocks, tool results, reply end, etc.
        })
        .blockLast();
```

Full event-type and field reference: [Message and event](./message-and-event.md).

### observe

Use `observe` to inject a message into the agent's context without triggering a reply — useful in multi-agent setups where one agent observes another agent's output.

```java
agent.observe(otherAgentMsg).block();
```

## RuntimeContext (per-call context)

`RuntimeContext` (`io.agentscope.core.agent.RuntimeContext`) is a **per-call metadata bag**: pass one instance to `call` / `stream`, and the agent binds it for the duration of that call so downstream tools, middlewares, and hooks all observe the same reference. The framework unbinds it on completion.

It is **not** persistent state — `AgentState` (conversation context, compressed summaries, permission rules, tool state) covers that. `RuntimeContext` carries data that is scoped to a single invocation: tenant / userId / request-id, DB connections, audit loggers, feature flags, and so on.

### Built-in fields and attribute layers

`RuntimeContext` exposes three kinds of slot:

| Slot | Set via | Read via |
|------|---------|----------|
| Session fields | `sessionId(String)` / `userId(String)` / `sessionKey(SessionKey)` | `getSessionId()` / `getUserId()` / `getSessionKey()` |
| String attributes (free-form key-value) | `put(String key, Object value)` | `<T> T get(String key)` |
| Typed attributes (inject business POJOs by `Class<T>`) | `put(Class<T> type, T value)` / `put(String key, Class<T> type, T value)` | `<T> T get(Class<T> type)` / `<T> T get(String key, Class<T> type)` |

Typed attributes power tool injection — declare a parameter of the matching type on a `@Tool` method and the framework supplies the value. See [Tool — Receiving context](./tool.md#receiving-context). String attributes are typically used for in-process coordination (e.g. middleware-to-middleware signalling). The two layers are isolated: typed values do not appear in `getExtra()` and vice-versa.

### Construct and pass

```java
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.UserMessage;
import io.agentscope.core.state.SimpleSessionKey;
import java.util.List;

RuntimeContext ctx =
        RuntimeContext.builder()
                .userId("alice")
                .sessionId("session-001")
                .sessionKey(SimpleSessionKey.of("alice:assistant:session-001"))
                .put("request_id", "req-abc-123")                            // string layer
                .put(UserContext.class, new UserContext("alice", "en"))      // typed layer (POJO)
                .build();

Msg result = agent.call(List.of(new UserMessage("Hi.")), ctx).block();
```

`ReActAgent` provides `RuntimeContext` overloads for `call` and `stream`; `streamEvents` does not — when you need a context with the event stream, use `stream(msgs, options, ctx)`, or configure a global `toolExecutionContext` on the builder. When no context is passed the framework substitutes `RuntimeContext.empty()` (null session fields, empty attribute maps).

### Who reads it

- **Tools** (`@Tool` methods and `ToolBase.callAsync`) — see [Tool — Receiving context](./tool.md#receiving-context).
- **Middleware** (every `MiddlewareBase` hook) — call `agent.getRuntimeContext()`. See [Middleware — Reading RuntimeContext](./middleware.md#reading-runtimecontext).
- **All threads within the same call** — the internal maps are `ConcurrentMap`s, so hooks and tools can read/write the same instance to coordinate.

### Relation to persistence

- `RuntimeContext` fields never enter `AgentState` and are never written back by `Session`.
- The `sessionKey` field is a convenience for the business layer; persistence still uses the `sessionKey()` configured on the builder. Setting it on `RuntimeContext` at runtime does not retarget persistence.

Runnable examples: `agentscope-examples/documentation/.../context/RuntimeContextExample.java`, `tool/ToolExecutionContextExample.java`.

:::{note}
A legacy `ToolExecutionContext` (`io.agentscope.core.tool`) is `@Deprecated`. New code should use `RuntimeContext`. The legacy type is bridged automatically via `RuntimeContext.asToolExecutionContext()`, so existing code keeps working.
:::

## Human-in-the-loop

The agent pauses and emits a special event in two cases: a tool call requiring **user confirmation** (the permission system returned ASK), or a tool marked as **external execution** (the result must come from outside the agent). In both cases, you resume the agent by feeding the result back through the next `call`.

### User confirmation

When the permission system decides a tool call needs user approval, the agent emits `RequireUserConfirmEvent` and pauses.

**1. Receive `RequireUserConfirmEvent`** — use `streamEvents` to detect the pause. The event carries `getReplyId()` (used to resume) and `getToolCalls()` — a list of `ToolUseBlock` each exposing `getId()` / `getName()` / `getInput()` / `getSuggestedRules()`.

```java
import io.agentscope.core.event.RequireUserConfirmEvent;

agent.streamEvents(msg)
        .doOnNext(event -> {
            if (event instanceof RequireUserConfirmEvent confirm) {
                confirm.getToolCalls().forEach(tc -> {
                    System.out.println("Tool: " + tc.getName() + ", input: " + tc.getInput());
                    System.out.println("Suggested rules: " + tc.getSuggestedRules());
                });
            }
        })
        .blockLast();
```

**2. Build confirm results** — construct a `ConfirmResult` per pending call. You can tweak the tool input on the way back, or accept the suggested rules so identical future calls auto-allow:

```java
import io.agentscope.core.event.ConfirmResult;
import java.util.ArrayList;
import java.util.List;

List<ConfirmResult> confirmResults = new ArrayList<>();
for (var tc : confirmEvent.getToolCalls()) {
    confirmResults.add(
            new ConfirmResult(
                    /* confirmed = */ true,                  // false to deny
                    /* toolCall  = */ tc,                    // pass back (optionally modified)
                    /* rules     = */ tc.getSuggestedRules() // accept rules → future calls auto-allow
                    ));
}
```

**3. Resume the agent** — pass `confirmResults` to the next `call` via metadata:

```java
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.UserMessage;

UserMessage resumeMsg =
        UserMessage.builder()
                .metadata(java.util.Map.of(
                        Msg.METADATA_CONFIRM_RESULTS, confirmResults))
                .build();

Msg result = agent.call(List.of(resumeMsg), RuntimeContext.empty()).block();
```

- **Confirmed** tool calls execute immediately; the agent continues reasoning.
- **Denied** tool calls produce an error result visible to the LLM, which may try a different approach.
- **Accepted rules** are persisted in the permission engine — matching future calls will be auto-allowed without prompting.

### External tool execution

When the agent invokes a tool with `isExternalTool() == true`, it emits `RequireExternalExecutionEvent` and pauses. The tool's logic runs outside the agent — typically by a human operator or external system.

**1. Receive `RequireExternalExecutionEvent`** — same shape as user confirmation: `getReplyId()` plus a list of `getToolCalls()` awaiting external execution.

```java
import io.agentscope.core.event.RequireExternalExecutionEvent;

agent.streamEvents(msg)
        .doOnNext(event -> {
            if (event instanceof RequireExternalExecutionEvent ext) {
                ext.getToolCalls().forEach(tc ->
                        System.out.println("External execution: " + tc.getName() + "(" + tc.getInput() + ")"));
            }
        })
        .blockLast();
```

**2. Execute externally and build results** — run the action outside the agent and wrap each result as a `ToolResultBlock`:

```java
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.message.ToolResultBlock;
import io.agentscope.core.message.ToolResultState;
import java.util.ArrayList;
import java.util.List;

List<ToolResultBlock> executionResults = new ArrayList<>();
for (var tc : externalEvent.getToolCalls()) {
    String output = runExternalOperation(tc.getName(), tc.getInput());
    executionResults.add(
            ToolResultBlock.builder()
                    .id(tc.getId())
                    .name(tc.getName())
                    .output(List.of(TextBlock.builder().text(output).build()))
                    .state(ToolResultState.SUCCESS)
                    .build());
}
```

**3. Resume the agent** — feed the results back as the next `call`'s input message. The results are injected into the agent context and reasoning continues from where it paused. See `agentscope-examples/documentation/.../hitl/InterruptionExample.java` for a complete walkthrough.

:::{tip}
Use `streamEvents` when building interactive UIs — it lets you detect pauses in real time and prompt the user immediately. Use `call` for programmatic flows that handle events automatically. Complete runnable examples: `agentscope-examples/documentation/.../hitl/PermissionHITLExample.java`.
:::

## Configuring and using Session

`AgentState` holds everything required to resume the agent — conversation context, compressed summaries, permission rules, tool state, and the current reply position. `Session` is its storage abstraction.

**Set `session(...)` and `sessionKey(...)` on the builder and the agent persists and recovers automatically**: every `call` writes `AgentState` back; on the next startup with the same key, it loads.

```java
import io.agentscope.core.session.JsonSession;
import io.agentscope.core.state.SimpleSessionKey;
import java.nio.file.Paths;

ReActAgent agent = ReActAgent.builder()
        .name("my_agent")
        .sysPrompt("You are a helpful assistant.")
        .model(model)
        .toolkit(new Toolkit())
        .session(new JsonSession(Paths.get(System.getProperty("user.home"), ".agentscope/sessions")))
        .sessionKey(SimpleSessionKey.of("user_123:agent_456:session_789"))
        .build();

// Auto-loaded on startup if data exists for this key.
int loaded = agent.getState().getContext().size();

// Auto-persisted when the call completes.
agent.call(List.of(new UserMessage("Resume the previous task."))).block();
```

Built-in and extension implementations:

| Implementation | Module | When to use |
|----------------|--------|-------------|
| `InMemorySession` | `agentscope-core` | unit tests / single-process demos |
| `JsonSession` | `agentscope-core` | single-machine dev; JSON per `SessionKey` directory |
| `RedisSession` | `agentscope-extensions-session-redis` | multi-replica production; shared across processes and nodes |
| `MysqlSession` | `agentscope-extensions-session-mysql` | when state must live in a relational store (audit / reporting) |

The default `SimpleSessionKey.of(id)` is enough for most cases. For multi-dimensional partitioning like `(userId, agentId, sessionId)`, implement the `SessionKey` interface yourself.

`agent.getAgentState()` exposes the current snapshot for side-channel use (admin console, audit):

```java
AgentState state = agent.getAgentState();
state.getContext().size();         // current message count
String json = state.toJson();      // serialize to JSON
```

For full field-by-field details, cross-node continuation, and how Session interacts with compaction / Plan Mode / subagents, see [Harness — Context](../harness/context.md).

## Further reading

::::{grid} 2

:::{grid-item-card} Permission System
:link: ./permission-system.html

Control which tools the agent can call, and under what conditions.
:::

:::{grid-item-card} Middleware
:link: ./middleware.html

Intercept and modify agent behavior at the agent, reasoning, acting, and model-call hooks.
:::

::::
