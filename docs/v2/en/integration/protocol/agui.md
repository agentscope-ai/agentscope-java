# AG-UI

`agentscope-extensions-agui` consumes the 2.0 fine-grained event stream exposed by `ReActAgent.streamEvents(...)` and converts it into [AG-UI Protocol](https://github.com/ag-ui-protocol/ag-ui) events so front-end UIs (Vercel AG-UI, custom chat UIs) can render the Agent's runtime — text, tool calls, and reasoning (ThinkingBlock).

## When to use

- You need to feed AG-UI-compatible front-end components.
- You want users to see tool calls and the model's reasoning streaming in.

## Add the dependency

```xml
<dependency>
    <groupId>io.agentscope</groupId>
    <artifactId>agentscope-extensions-agui</artifactId>
    <version>${agentscope.version}</version>
</dependency>
```

## Quickstart

```java
import io.agentscope.core.agui.adapter.AguiAdapterConfig;
import io.agentscope.core.agui.adapter.AguiAgentAdapter;
import io.agentscope.core.agui.event.AguiEvent;
import io.agentscope.core.agui.model.RunAgentInput;
import reactor.core.publisher.Flux;

AguiAdapterConfig config = AguiAdapterConfig.builder()
    .enableReasoning(true)        // emit ThinkingBlock as REASONING_* events
    .runTimeout(Duration.ofMinutes(5))
    .build();

AguiAgentAdapter adapter = new AguiAgentAdapter(reactAgent, config);

// Events you'd ship to the front end via SSE
Flux<AguiEvent> events = adapter.run(runAgentInput);
```

`reactAgent` must be a `ReActAgent` — the adapter consumes its 2.0 fine-grained event stream via `ReActAgent.streamEvents(...)`. The front end provides `runAgentInput` (containing `threadId`, `runId`, `messages`, etc.). The adapter converts messages, invokes `streamEvents`, and maps the resulting `AgentEvent`s to AG-UI.

## Event mapping

| AgentScope `AgentEvent` | AG-UI event |
| --- | --- |
| `TextBlockStartEvent` / `TextBlockDeltaEvent` / `TextBlockEndEvent` | `TEXT_MESSAGE_START` / `TEXT_MESSAGE_CONTENT` / `TEXT_MESSAGE_END` |
| `ThinkingBlockStartEvent` / `ThinkingBlockDeltaEvent` / `ThinkingBlockEndEvent` | `REASONING_MESSAGE_START` / `REASONING_MESSAGE_CONTENT` / `REASONING_MESSAGE_END` (when `enableReasoning=true`) |
| `ToolCallStartEvent` / `ToolCallDeltaEvent` / `ToolCallEndEvent` | `TOOL_CALL_START` / `TOOL_CALL_ARGS` / `TOOL_CALL_END` |
| `ToolResultStartEvent` / `ToolResultTextDeltaEvent` / `ToolResultDataDeltaEvent` / `ToolResultEndEvent` | `TOOL_CALL_RESULT` (buffered; with a defensive `TOOL_CALL_START` / `TOOL_CALL_END` backfill when the tool call was not opened explicitly) |

## Spring Boot integration

The typical wire-up is a `@PostMapping("/ag-ui")` returning the `Flux<AguiEvent>` as SSE. Alternatively, use `agentscope-agui-spring-boot-starter` to register the controller automatically.

## Common configuration

| Field | Default | Notes |
| --- | --- | --- |
| `toolMergeMode` | `MERGE_FRONTEND_PRIORITY` | How front-end-defined tools merge with Agent-side tools |
| `emitStateEvents` | `true` | Emit `STATE_*` events (e.g. thread state) |
| `emitToolCallArgs` | `true` | Stream tool-call arguments |
| `enableReasoning` | `false` | Emit ThinkingBlock as REASONING_* events |
| `runTimeout` | `10m` | Per-run timeout |
| `defaultAgentId` | `null` | Default `agentId` when none is provided |
