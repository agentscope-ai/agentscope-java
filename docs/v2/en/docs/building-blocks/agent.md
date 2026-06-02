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

Build an agent with `ReActAgent.builder()...build()`. The two most common configurations:

::::{tab-set}
:::{tab-item} Minimal
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
import io.agentscope.core.formatter.dashscope.DashScopeChatFormatter;
import io.agentscope.core.model.DashScopeChatModel;
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
                .model(
                        DashScopeChatModel.builder()
                                .apiKey("YOUR_API_KEY")
                                .modelName("qwen-max")
                                .stream(true)
                                .formatter(new DashScopeChatFormatter())
                                .build())
                .toolkit(toolkit)
                .build();
```
:::
::::

### Builder fields

| Field | Type | Default | Description |
|-------|------|---------|-------------|
| `name` | `String` | required | Agent identifier, used for messages and logs |
| `sysPrompt` | `String` | required | The base system prompt |
| `model` | `Model` | required | The LLM driving reasoning (extends `ChatModelBase`) |
| `toolkit` | `Toolkit` | `new Toolkit()` | Manages tools, MCP clients, skills, and tool groups |
| `middlewares` | `List<? extends MiddlewareBase>` | `List.of()` | Applied to agent / reasoning / acting / model call / system prompt hooks |
| `session` + `sessionKey` | `Session` + `SessionKey` | `null` (no persistence) | When set, agent automatically loads/saves `AgentState` on every `call` |
| `permissionContext` | `PermissionContextState` | `DEFAULT` mode | Fine-grained tool execution rules, see [Permission System](/v2/building-blocks/permission-system) |
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

`streamEvents` emits `AgentEvent`s one by one so you can stream text, tool-call progress, and lifecycle events to your UI in real time.

```java
import io.agentscope.core.event.AgentEventType;
import io.agentscope.core.event.TextBlockDeltaEvent;

agent.streamEvents(new UserMessage("Summarize the README."))
        .doOnNext(event -> {
            if (event.getType() == AgentEventType.TEXT_BLOCK_DELTA) {
                System.out.print(((TextBlockDeltaEvent) event).getDelta());
            }
        })
        .blockLast();
```

### observe

Use `observe` to inject a message into the agent's context without triggering a reply — useful in multi-agent setups where one agent observes another agent's output.

```java
agent.observe(otherAgentMsg).block();
```

## Human-in-the-loop

The agent pauses and emits a special event in two cases: a tool call requiring **user confirmation** (the permission system returned ASK), or a tool marked as **external execution** (the result must come from outside the agent). In both cases, you resume the agent by feeding the result back through the next `call`.

### User confirmation

When the permission system decides a tool call needs user approval, the agent emits `RequireUserConfirmEvent` and pauses.

### Receive RequireUserConfirmEvent

Use `streamEvents` to detect the pause:

    - **`getReplyId()` · `String` · *required*** — ID of the current reply, used to resume the agent.
    - **`getToolCalls()` · `List<ToolUseBlock>` · *required*** — List of tool calls awaiting user confirmation. Each `ToolUseBlock` provides:

      :::{dropdown} Details
      - **`getId()` · `String`** — Unique tool-call ID.
      - **`getName()` · `String`** — Tool name (e.g. `"todo_write"`, `"my_tool"`).
      - **`getInput()` · `Map<String, Object>`** — Parsed input arguments.
      - **`getSuggestedRules()` · `List<PermissionRule>`** — Auto-generated permission rules the user can accept to allow similar future calls.
      :::

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

  ### Build confirm results

Construct a `ConfirmResult` per pending tool call to allow / deny execution. You can also tweak the tool call input or accept the suggested rules:

    ```java
    import io.agentscope.core.event.ConfirmResult;
    import java.util.ArrayList;
    import java.util.List;

    List<ConfirmResult> confirmResults = new ArrayList<>();
    for (var tc : confirmEvent.getToolCalls()) {
        confirmResults.add(
                new ConfirmResult(
                        /* confirmed = */ true,                 // false to deny
                        /* toolCall  = */ tc,                   // pass back (optionally modified)
                        /* rules     = */ tc.getSuggestedRules() // accept rules so future calls auto-allow
                        ));
    }
    ```

  ### Resume the agent

Pass `confirmResults` to the next `call` via metadata:

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

### Receive RequireExternalExecutionEvent

Event shape:

    - **`getReplyId()` · `String` · *required*** — ID of the current reply, used to resume the agent.
    - **`getToolCalls()`** — " required>
      List of tool calls awaiting external execution.

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

  ### Execute externally and build results

Run the action outside the agent and wrap each result as a `ToolResultBlock`:

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

  ### Resume the agent

Pass the `ExternalExecutionResultEvent`-equivalent message back to resume. See `agentscope-examples/documentation/.../hitl/InterruptionExample.java` for a complete walkthrough. The results are injected into the agent context, and reasoning continues from where it paused.

:::{tip}
Use `streamEvents` when building interactive UIs — it lets you detect pauses in real time and prompt the user immediately. Use `call` for programmatic flows that handle events automatically. Complete runnable examples: `agentscope-examples/documentation/.../hitl/PermissionHITLExample.java`.
:::

## Persisting agent state

`AgentState` holds everything required to resume the agent — conversation context, compressed summaries, permission rules, tool state, and the current reply position. It implements `State`, can be serialised to JSON, and stored on any backend.

The `Session` interface abstracts state storage. Two built-in implementations:

| Implementation | Description |
|----------------|-------------|
| `InMemorySession` | Process-local map, useful for unit tests |
| `JsonSession` | Filesystem JSON persistence, partitioned by `SessionKey` |

Configure both on the builder and the agent will auto-save `AgentState` after every `call`. On startup, if data already exists for the given key, it is loaded automatically.

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

public class PersistDemo {
    public static void main(String[] args) {
        Path sessionDir = Paths.get(System.getProperty("user.home"), ".agentscope", "sessions");
        Session session = new JsonSession(sessionDir);

        ReActAgent agent =
                ReActAgent.builder()
                        .name("my_agent")
                        .sysPrompt("You are a helpful assistant.")
                        .model(
                                DashScopeChatModel.builder()
                                        .apiKey(System.getenv("DASHSCOPE_API_KEY"))
                                        .modelName("qwen-max")
                                        .stream(true)
                                        .formatter(new DashScopeChatFormatter())
                                        .build())
                        .toolkit(new Toolkit())
                        .session(session)
                        .sessionKey(SimpleSessionKey.of("user_123:agent_456:session_789"))
                        .build();

        // On startup, existing data for this key is auto-loaded into AgentState.
        int loaded = agent.getState().getContext().size();
        System.out.println("loaded " + loaded + " message(s) from session");

        // One reply round — auto-persisted on completion.
        Msg result =
                agent.call(List.of(new UserMessage("Resume the previous task."))).block();
        System.out.println(result.getTextContent());
    }
}
```

:::{note}
The default `SessionKey` implementation `SimpleSessionKey` takes a single ID string. For multi-dimensional partitioning like `(userId, agentId, sessionId)`, implement the `SessionKey` interface yourself (see the `SessionKey.java` javadoc example).
:::

## Further reading

::::{grid} 2

:::{grid-item-card} Permission System
:link: /v2/building-blocks/permission-system

Control which tools the agent can call, and under what conditions.
:::
  :::{grid-item-card} Middleware
:link: /v2/building-blocks/middleware

Intercept and modify agent behavior at the agent, reasoning, acting, and model-call hooks.
:::

::::
