# AG-UI

## Compatibility Notes

`agentscope-extensions-agui` converts AgentScope v2 `AgentEvent` streams into [AG-UI Protocol](https://github.com/ag-ui-protocol/ag-ui) events so front-end UIs can render an agent run in real time, including text, reasoning, tool calls, state, custom events, token usage, and HITL interrupts.

`RUN_ERROR` and `RUN_FINISHED` are mutually exclusive terminal events. Set `emitRunFinishedAfterError=true` only if you still need the legacy `RUN_ERROR` + `RUN_FINISHED` sequence.

`AguiMessage.content` is represented as typed message content. For text-only code paths, use `getTextContent()`.

Multimodal input is supported, but document types are not supported yet.

`AguiMessageConverter.toAguiMessage()` currently preserves text and tool-call fields only; image, audio, video, and document content blocks are not serialized back into AG-UI message content.

## When To Use

- You need to connect an AgentScope agent to an AG-UI-compatible front end or a custom chat UI.
- You need to stream `RUN_*`, `TEXT_MESSAGE_*`, `TOOL_CALL_*`, `CUSTOM`, and related AG-UI events over SSE.
- You need frontend tools, user-approval interrupts, runtime context propagation, or custom event-conversion extensions.

## Add Dependencies

For manual adapter usage, add:

```xml
<dependency>
    <groupId>io.agentscope</groupId>
    <artifactId>agentscope-extensions-agui</artifactId>
    <version>${agentscope.version}</version>
</dependency>
```

Spring Boot applications can use the starter:

```xml
<dependency>
    <groupId>io.agentscope</groupId>
    <artifactId>agentscope-agui-spring-boot-starter</artifactId>
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
    .enableReasoning(true)
    .emitTokenUsage(true)
    .runTimeout(Duration.ofMinutes(5))
    .build();

AguiAgentAdapter adapter = new AguiAgentAdapter(agent, config);

// Events you'd ship to the front end via SSE
Flux<AguiEvent> events = adapter.run(runAgentInput);
```

The front end provides `RunAgentInput`, including `threadId`, `runId`, `messages`, `tools`, `state`, and related fields. The adapter converts AG-UI messages to AgentScope `Msg` objects, invokes v2 `streamEvents(...)`, and converts each `AgentEvent` to AG-UI events.

## Event Mapping

The v2 path consumes `AgentEvent`. Built-in converters handle semantic mapping, and unmapped events fall back to the official `RAW` event.

| AgentScope event / content             | AG-UI event |
|----------------------------------------| --- |
| `AgentStartEvent`                      | `RUN_STARTED` |
| `AgentEndEvent`                        | `RUN_FINISHED` |
| Text                              | `TEXT_MESSAGE_START` / `TEXT_MESSAGE_CONTENT` / `TEXT_MESSAGE_END` |
| Thinking (`enableReasoning=true`) | `REASONING_MESSAGE_START` / `REASONING_MESSAGE_CONTENT` / `REASONING_MESSAGE_END` |
| Tool-call and argument deltas          | `TOOL_CALL_START` / `TOOL_CALL_ARGS` / `TOOL_CALL_END` |
| Tool result                            | `TOOL_CALL_RESULT` |
| `CustomEvent`                          | `CUSTOM` |
| token usage (`emitTokenUsage=true`)    | `CUSTOM`, `name=token_usage` |
| Unmapped `AgentEvent`                  | `RAW`, with official `event` and `source` fields |

Normal `RUN_STARTED` and `RUN_FINISHED` events are driven by upstream `AgentStartEvent` and `AgentEndEvent`. If a normal stream completes without an upstream `AgentEndEvent`, the adapter does not synthesize `RUN_FINISHED`. On errors, the adapter emits a `RUN_ERROR` with a `timestamp`. `RUN_ERROR` and `RUN_FINISHED` are mutually exclusive terminal events. Set `emitRunFinishedAfterError=true` (or Spring Boot `agentscope.agui.emit-run-finished-after-error=true`) only for legacy clients that still expect a finish event after an error.

## Subagent events

By default (`emitSubagentEventsAsNative=false`), AgentEvents with a non-null `source` (child / remote subagent events) are **not** mapped to native `TEXT_MESSAGE_*` / `RUN_*` / tool-call events. They become AG-UI `CUSTOM` events under the `subagent.*` namespace so they do not pollute the parent run lifecycle or text stream:

| CUSTOM `name` | Typical AgentEvent |
| --- | --- |
| `subagent.lifecycle` | `AgentStartEvent` / `AgentEndEvent` |
| `subagent.text` | `TextBlockDeltaEvent` |
| `subagent.thinking` | `ThinkingBlockDeltaEvent` |
| `subagent.tool_call` | `ToolCallStartEvent` / `ToolCallEndEvent` |
| `subagent.tool_result` | `ToolResultEndEvent` |
| `subagent.require_confirm` | `RequireUserConfirmEvent` |

Each payload includes at least `source` and `type` (plus type-specific fields such as `delta` or `toolCallId`).

To restore the previous behavior where child events used the same native converters as the parent:

```java
AguiAdapterConfig config = AguiAdapterConfig.builder()
    .emitSubagentEventsAsNative(true)
    .build();
```

## AG-UI Base Event Properties

Every `AguiEvent` supports the official base event properties: optional `timestamp` and `rawEvent`.

The default config does not enable `BaseEventPropertiesEnricher`, so the framework does not add `timestamp` to every event by default and does not expose internal `AgentEvent` objects as `rawEvent` by default. Enable the default enricher explicitly if you want timestamps filled:

```java
AguiAdapterConfig config = AguiAdapterConfig.builder()
    .baseEventPropertiesEnricherEnabled(true)
    .build();
```

`BaseEventPropertiesEnricher` only fills a missing `timestamp`; it preserves existing timestamps and does not write `rawEvent`. To expose `rawEvent`, register a custom `AguiEventEnricher`.

The Spring Boot starter does not implicitly enable the default base properties enricher. If you want that behavior, expose a `BaseEventPropertiesEnricher` bean or your own `AguiEventEnricher` bean.

## Custom Converters And Enrichers

`AgentEventConverter` extends or overrides semantic mapping. For the same `AgentEvent` type, a user converter overrides the built-in converter.

```java
@Bean
AgentEventConverter customEventConverter() {
    return new AgentEventConverter() {
        @Override
        public Set<Class<? extends AgentEvent>> eventTypes() {
            return Set.of(CustomEvent.class);
        }

        @Override
        public void convert(AgentEvent event, AguiStreamContext context) {
            CustomEvent customEvent = (CustomEvent) event;
            context.emit(new AguiEvent.Custom(
                context.getThreadId(),
                context.getRunId(),
                customEvent.getName(),
                customEvent.getValue()));
        }
    };
}
```

`AguiEventEnricher` runs after conversion. It is intended for cross-cutting concerns such as `timestamp`, `rawEvent`, tracing fields, or other event decoration. It may modify, append, or filter converter output.

```java
@Bean
AguiEventEnricher timestampEnricher() {
    return (source, events, context) -> events.stream()
        .map(event -> AguiEvents.withBaseProperties(
            event,
            event.timestamp() != null ? event.timestamp() : System.currentTimeMillis(),
            event.rawEvent()))
        .toList();
}
```

The Spring Boot starter automatically collects `AgentEventConverter` and `AguiEventEnricher` beans and uses `orderedStream()` so `@Order` / `Ordered` are honored.

## Token Usage

Token usage is disabled by default. Manual config:

```java
AguiAdapterConfig config = AguiAdapterConfig.builder()
    .emitTokenUsage(true)
    .build();
```

Spring Boot config:

```yaml
agentscope:
  agui:
    emit-token-usage: true
```

When enabled, every `ModelCallEndEvent` with usage emits a `CUSTOM` event: `delta` is the current model-call usage. `cumulative` is the accumulated usage within the current AG-UI run.

## RuntimeContext

`AguiAgentAdapter.run(input, runtimeContext)` accepts a caller-provided `RuntimeContext`. The adapter copies the caller context first, then applies AG-UI protocol metadata so the required defaults are not lost.

| RuntimeContext entry | Source |
| --- | --- |
| `sessionId` | `RunAgentInput.threadId` |
| `RunAgentInput.class` | Full `RunAgentInput` |
| `agui.threadId` | `RunAgentInput.threadId` |
| `agui.runId` | `RunAgentInput.runId` |
| `agui.messages` | `RunAgentInput.messages` |
| `agui.tools` | `RunAgentInput.tools` |
| `agui.context` | `RunAgentInput.context` |
| `agui.state` | `RunAgentInput.state` |
| `agui.forwardedProps` | `RunAgentInput.forwardedProps` |
| `agui.resume` | `RunAgentInput.resume` |

Because `sessionId` always comes from `threadId`, the same agent instance remains isolated across AG-UI threads.

## Spring Boot Integration

The starter registers MVC or WebFlux endpoints automatically. Common config:

```yaml
agentscope:
  agui:
    path-prefix: /agui
    cors-enabled: true
    run-timeout: 10m
    default-agent-id: default
    enable-path-routing: true
    agent-id-header: X-Agent-Id
    emit-state-events: true
    emit-tool-call-args: true
    emit-token-usage: false
    enable-reasoning: false
    emit-run-finished-after-error: false
    server-side-memory: false
    interrupt-on-disconnect: true
```

`interrupt-on-disconnect` controls whether an Agent run is interrupted when the MVC/WebFlux SSE
connection is closed, times out, or fails while sending an event. It defaults to `true` for
backward compatibility. Set it to `false` to let the Agent continue running after the client
disconnects; events produced while the connection is closed are not replayed by the starter.

You can extend the default chain with beans:

- `AgentEventConverter`: custom event semantic mapping.
- `AguiEventEnricher`: cross-cutting event enrichment.
- `AguiRuntimeContextResolver`: request-scoped `RuntimeContext` injection.
- `AguiAgentAdapterFactory`: replacement for default `AguiAgentAdapter` construction.

`AguiRuntimeContextResolver` can read the transport, path agent id, header agent id, headers, query params, and native Web request.

```java
@Bean
AguiRuntimeContextResolver runtimeContextResolver() {
    return request -> RuntimeContext.builder()
        .put("tenantId", request.firstHeader("X-Tenant-Id"))
        .put("traceId", request.firstHeader("X-Trace-Id"))
        .build();
}
```

`forwardedProps` comes from the client request body and is suitable for UI options or frontend context. Do not treat it as a trusted identity source; server-side user identity should come from authentication or a server-side resolver.

## Frontend Tools And Merge Mode

An AG-UI front end can pass tool schemas through `RunAgentInput.tools`. The adapter injects those tools into the agent toolkit at the start of one run and cleans them up after the run completes or is cancelled.

| `ToolMergeMode` | Behavior |
| --- | --- |
| `FRONTEND_ONLY` | Use only frontend-provided tools and temporarily hide existing agent tools |
| `AGENT_ONLY` | Ignore frontend-provided tools and use only the agent toolkit |
| `MERGE_FRONTEND_PRIORITY` | Merge both sides; frontend tools win on name conflicts |

The default is `MERGE_FRONTEND_PRIORITY`. Injection is run scoped and does not permanently mutate the agent toolkit.

## HITL Interrupts

When a run pauses for a tool decision, the AG-UI adapter emits the official interrupt outcome on `RUN_FINISHED`. AgentScope Java has two built-in tool-call interrupt paths:

- **Tool suspension / external execution**: a suspended `ToolResultBlock` becomes a `tool_call` interrupt and resumes as a `ToolResultBlock`.
- **Permission confirmation**: `RequireUserConfirmEvent` becomes a `tool_call` interrupt with AgentScope metadata and resumes as a `ConfirmResult`.

Both use the official AG-UI `reason: "tool_call"` because the interrupt is bound to a specific `toolCallId`. Do not use `reason: "confirmation"` for these tool-bound approvals.

```json
{
  "type": "RUN_FINISHED",
  "outcome": {
    "type": "interrupt",
    "interrupts": [
      {
        "id": "reply-1:call-1",
        "reason": "tool_call",
        "toolCallId": "call-1",
        "message": "Need approval before running this tool",
        "responseSchema": {
          "type": "object",
          "properties": {
            "approved": { "type": "boolean" },
            "editedArgs": {
              "type": "object",
              "description": "Full replacement of the tool args. Not merged."
            }
          },
          "required": ["approved"]
        },
        "metadata": {
          "agentscope.interruptKind": "permission_confirm",
          "toolName": "request_approval",
          "toolInput": { "path": "/tmp/report.txt" },
          "toolContent": "{\"path\":\"/tmp/report.txt\"}",
          "replyId": "reply-1"
        }
      }
    ]
  }
}
```

The front end can show an approval or external-execution UI. After the user acts, send the official `resume[]` field on the next `runAgent` request for the same `threadId`:

```json
{
  "threadId": "thread-1",
  "runId": "run-2",
  "messages": [],
  "resume": [
    {
      "interruptId": "reply-1:call-1",
      "status": "resolved",
      "payload": {
        "approved": true,
        "editedArgs": {
          "path": "/tmp/reviewed-report.txt"
        }
      }
    }
  ]
}
```

`status` supports the official `resolved` and `cancelled` values. For the common approval case where a user rejects a tool request, prefer `resolved` and express the business decision in `payload`, for example `{ "approved": false }`; use `cancelled` when the interrupt itself is cancelled.

For permission confirmations, `payload.approved` must be the boolean `true` to approve the tool. Any missing, non-boolean, or `false` value is treated as denial. `payload.editedArgs`, when present, must be a JSON object and is a **full replacement** of the original tool arguments, not a partial merge. AgentScope Java rebuilds both the `ToolUseBlock.input` and raw JSON `ToolUseBlock.content` from `editedArgs`, so the approved tool executes the edited arguments.

The front end does not need to echo `metadata` in `resume[]`; it only sends `interruptId`, `status`, and `payload`. Through the Spring `AguiRequestProcessor` entry point, AgentScope Java records the latest `RUN_FINISHED.outcome.interrupts[]` server-side, validates that the next `resume[]` covers all open interrupts, and passes the originating interrupts into the adapter for conversion.

## Example Project

See the complete example at [agentscope-examples/agui](https://github.com/agentscope-ai/agentscope-java/tree/main/agentscope-examples/agui):

```bash
export DASHSCOPE_API_KEY=your-key
cd agentscope-examples/agui
mvn spring-boot:run
```

Visit http://localhost:8080 after startup. The example demonstrates multi-agent routing, custom converters, custom enrichers, token usage, and HITL interrupts.
