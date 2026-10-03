---
title: AG-UI
zh_link: /v2/zh/integration/protocol/agui
---

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

An AG-UI front end can pass tool schemas through `RunAgentInput.tools`. The adapter converts them into a run-scoped `ToolRequestConfig` carried by RuntimeContext. It never mutates the agent toolkit, so completion and cancellation require no registry restoration.

| `ToolMergeMode` | Behavior |
| --- | --- |
| `EXTERNAL_ONLY` | Use only frontend-provided tools and temporarily hide existing agent tools |
| `AGENT_ONLY` | Ignore frontend-provided tools and use only the agent toolkit |
| `MERGE_EXTERNAL_PRIORITY` | Merge both sides; frontend tools win on name conflicts |

The default is `MERGE_EXTERNAL_PRIORITY`. Import the enum from `io.agentscope.core.tool.ToolMergeMode`. `EXTERNAL_ONLY` exposes no tools when the external list is empty, regardless of the Toolkit deletion policy: it controls request visibility only.

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
            },
            "reason": {
              "type": "string",
              "description": "Optional explanation supplied when the tool call is denied."
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

`payload.reason` is an optional string. On denial it becomes `ConfirmResult.reason` and is used as the DENIED tool-result text; when it is missing or blank, AgentScope keeps the default `Permission denied by user` message.

The front end does not need to echo `metadata` in `resume[]`; it only sends `interruptId`, `status`, and `payload`. Through the Spring `AguiRequestProcessor` entry point, AgentScope Java records the latest `RUN_FINISHED.outcome.interrupts[]` server-side, validates that the next `resume[]` covers all open interrupts, and passes the originating interrupts into the adapter for conversion.

## Shared resume state and failure recovery

`AguiResumeStateStore` coordinates active runs and pending interrupts. The default store is local to
one processor. For multiple replicas, supply the same shared store through
`AguiRequestProcessor.Builder.resumeStateStore(...)` or expose an `AguiResumeStateStore` Spring bean.
`RedisAguiResumeStateStore` uses a caller-owned Jedis client; configure finite connection, pool-wait,
and socket timeouts on that client.

The processor runs synchronous store calls on Reactor's `boundedElastic` scheduler, including
pending writes and cleanup after disconnect. Subscription and cancellation cleanup are asynchronous;
`dispose()` does not mean ownership has already been released. When `interruptOnDisconnect` is enabled,
the Spring transports cancel the subscription and its lifecycle interrupts the validated execution on
`boundedElastic` before releasing ownership. Invalid, rejected or indeterminate requests cannot interrupt
another execution, and late disconnects cannot interrupt a newer owner. Interruption runs once, outside
the release retry budget; an interruption failure is logged and ownership release still proceeds.
A tool that ignores cancellation can continue executing.

Direct processor users can enable this behavior with `Builder.interruptOnCancel(true)` (default false).
The explicit `ProcessResult.interrupt(threadId)` method remains a session-wide administrative operation;
it does not provide the ownership protection of cancelling the configured subscription.

Claim, validation, resume injection, and pending-write failures become `RUN_ERROR`. The processor emits
`RUN_STARTED` first if needed and follows `emitRunFinishedAfterError` for the optional final event.
A failed pending write does not forward the successful `RUN_FINISHED` event. Storage failures are not
interpreted as empty pending state and do not automatically rerun the agent.

For a confirmed claim, a resume contract error starts owner-checked cleanup before its error events
are delivered, even when the subscriber requests no events. Pending interrupts remain available for
a later valid resume. Early cleanup, completion, error, and cancellation share one cleanup process
per subscription, including a total limit of three attempts with 50 ms and 100 ms backoffs. Delayed
error consumption or cancellation does not restart that budget. Failed attempts log `WARN`; recovery logs
`INFO`; exhaustion logs `ERROR` with `threadId`, `runId`, phase, attempt count, and the exception.
Configure an SLF4J logging provider and alert on `AG-UI release exhausted`. Cleanup errors are logged
rather than appended after a terminal event or delivered as Reactor dropped errors. If processing also
failed, the final cleanup error is attached to that original exception as a suppressed exception.

This is bounded best-effort recovery, not a lease or crash-recovery mechanism. Pending state and owners
have no TTL. A claim whose reply is lost has an unknown outcome: it produces an error but is not blindly
released or retried. Use a fresh `runId` for each execution: it is the ownership token, and reusing it
can allow a delayed release to affect a later execution.

If an owner remains after retries or a process failure:

1. Use the logged thread/run IDs to investigate the old execution. Stop or confirm termination of its
   agent and tools, and wait for its cleanup attempts to end. A disconnected client is not proof that
   business work has stopped.
2. After that confirmation, call `sharedStore.releaseRun(threadId, oldRunId)` from an administrative
   path. This performs an atomic owner check and preserves pending interrupts; do not delete the
   entire Redis key or clear pending state to unlock the thread.
3. Inspect pending interrupts and retry with a new run ID and a matching `resume[]`. Decide separately
   how to reconcile external side effects or an interrupted pending-state write.

## Example Project

See the complete example at [agentscope-examples/agui](https://github.com/agentscope-ai/agentscope-java/tree/main/agentscope-examples/agui):

```bash
export DASHSCOPE_API_KEY=your-key
cd agentscope-examples/agui
mvn spring-boot:run
```

Visit http://localhost:8080 after startup. The example demonstrates multi-agent routing, custom converters, custom enrichers, token usage, and HITL interrupts.
