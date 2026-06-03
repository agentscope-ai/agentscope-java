# AG-UI

`agentscope-extensions-agui` converts AgentScope event streams into [AG-UI Protocol](https://github.com/ag-ui-protocol/ag-ui) events so front-end UIs (Vercel AG-UI, custom chat UIs) can render the Agent's runtime — text, tool calls, and reasoning (ThinkingBlock).

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

AguiAgentAdapter adapter = new AguiAgentAdapter(agent, config);

// Events you'd ship to the front end via SSE
Flux<AguiEvent> events = adapter.run(runAgentInput);
```

The front end provides `runAgentInput` (containing `threadId`, `runId`, `messages`, etc.). The adapter converts messages, calls the Agent's streaming API, and maps the events to AG-UI.

## Event mapping

| AgentScope event / block | AG-UI event |
| --- | --- |
| `EventType.REASONING / SUMMARY` with `TextBlock` | `TEXT_MESSAGE_*` |
| `EventType.REASONING / SUMMARY` with `ThinkingBlock` | `REASONING_*` (when `enableReasoning=true`) |
| `ToolUseBlock` | `TOOL_CALL_START` |
| `EventType.TOOL_RESULT` | `TOOL_CALL_END` |

## Spring Boot integration

The typical wire-up is a `@PostMapping("/ag-ui")` returning the `Flux<AguiEvent>` as SSE. Alternatively, use `agentscope-spring-boot-starter-agui` to register the controller automatically.

## Common configuration

| Field | Default | Notes |
| --- | --- | --- |
| `toolMergeMode` | `MERGE_FRONTEND_PRIORITY` | How front-end-defined tools merge with Agent-side tools |
| `emitStateEvents` | `true` | Emit `STATE_*` events (e.g. thread state) |
| `emitToolCallArgs` | `true` | Stream tool-call arguments |
| `enableReasoning` | `false` | Emit ThinkingBlock as REASONING_* events |
| `runTimeout` | `10m` | Per-run timeout |
| `defaultAgentId` | `null` | Default `agentId` when none is provided |
