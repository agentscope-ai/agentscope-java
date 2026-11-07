---
title: AG-UI
zh_link: /v2/zh/integration/protocol/agui
---

## Compatibility Notes

`agentscope-extensions-agui` converts AgentScope v2 `AgentEvent` streams into [AG-UI Protocol](https://github.com/ag-ui-protocol/ag-ui) events so front-end UIs can render an agent run in real time, including text, reasoning, tool calls, state, custom events, token usage, and HITL interrupts.

`RUN_ERROR` and `RUN_FINISHED` are mutually exclusive terminal events. Set `emitRunFinishedAfterError=true` only if you still need the legacy `RUN_ERROR` + `RUN_FINISHED` sequence.

`AguiMessage.content` is represented as typed message content. For text-only code paths, use `getTextContent()`.

Multimodal input supports image/audio/video/document. Document parts are converted to `DocumentBlock`; model providers that cannot consume a document report an error instead of silently dropping the content.

`RUN_STARTED.input` retains the original request, so consumers that log or forward events must redact sensitive input themselves.

`AguiMessageConverter.toAguiMessageList()` expands a TOOL `Msg` containing multiple `ToolResultBlock`s into one AG-UI tool message per tool result. `toAguiMessage()` remains for messages with at most one tool result and rejects a multi-result TOOL message; use `toAguiMessages()` for the expanded form.

On the outbound path, URL-backed `ImageBlock` / `AudioBlock` / `VideoBlock` / `DocumentBlock` content is serialized into AG-UI `MessageContent.Blocks` (`image` / `audio` / `video` / `document`); URL-backed `DataBlock` is routed by its resolved MIME type. Base64 media sources are skipped on outbound conversion for now.

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

Configure a shared `HarnessAgent.Builder agentBuilder` as in the [Quickstart](/v2/en/docs/quickstart). Build an Agent for each request and close it when the SSE stream ends or is cancelled:

```java
import io.agentscope.core.agui.adapter.AguiAdapterConfig;
import io.agentscope.core.agui.adapter.AguiAgentAdapter;
import io.agentscope.core.agui.event.AguiEvent;
import io.agentscope.core.agui.model.RunAgentInput;
import io.agentscope.harness.agent.HarnessAgent;
import java.time.Duration;
import reactor.core.publisher.Flux;

AguiAdapterConfig config = AguiAdapterConfig.builder()
    .enableReasoning(true)
    .emitTokenUsage(true)
    .runTimeout(Duration.ofMinutes(5))
    .build();

Flux<AguiEvent> events = Flux.using(
    agentBuilder::build,
    agent -> new AguiAgentAdapter(agent, config).run(runAgentInput),
    HarnessAgent::close);
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

## SessionLog recovery (HarnessAgent only)

Recovery here means one narrow case: an Agent run is still executing in the background, and the browser refreshes or restarts. The application then reattaches to that run. Loading a completed conversation, showing a suspended HITL interaction, or resuming an interrupted turn is application-level history/state handling, not AG-UI recovery.

The AG-UI recovery path is an AgentScope extension, not part of the AG-UI protocol. The standard `/agui/run` endpoint always starts an ordinary run; it does not infer recovery from empty messages.

### Requirements

- Execution is owned outside the browser request: retain a `HarnessAgent` and run the task through `AgentSession`, or keep the original run alive with `agentscope.agui.interrupt-on-disconnect=false` and a long-lived agent.
- The resolved agent is a `HarnessAgent` with `SessionHistoryMode.EVENT_LOG`.
- The application stores `threadId`, the execution `runId`, and the last applied cursor.
- History and `SessionLog` share the same message identities, including assistant tool calls.

### Application history + attach

The application supplies the base conversation and a cursor that is a presentation boundary:

1. Load committed messages from the application's history store.
2. Return `asOfSeq`: the last committed event covered by those messages. It must not include in-flight text or tool arguments that the history does not contain.
3. If the run is still executing, call the AgentScope attach extension from `asOfSeq`.

```json
{
  "threadId": "conversation-001",
  "runId": "recovery-1",
  "messages": [],
  "forwardedProps": {
    "agentscope": {
      "afterSequence": 42,
      "runId": "exec-1"
    }
  }
}
```

`afterSequence` may also come from `Last-Event-ID`. `runId` scopes the stream to one execution. This is an AgentScope extension.

The reusable entry point is `AguiRequestProcessor.attach(AguiRuntimeContextRequest)`, which returns `Flux<AguiFramedEvent>`. The application owns the HTTP endpoint, authorization, history store, and SSE writing; the starter does not add an implicit attach path to `/agui/run`.

### What attach does

Attach is the reconnect path of an existing run. It is a read-only observer: it never starts, resumes, or interrupts an Agent run, never calls a model, and never writes to the session log. The same cursor always produces the same events, so retrying a reconnect is safe.

- It replays committed events after the cursor, and exposes the cursor as the SSE `id` on the last frame produced by each committed event. Model chunks are streamed content, not replay boundaries: their frames carry the source sequence but no `id`, so a reconnect never lands in the middle of a message. Tool result ends and the run end are boundaries and carry the `id`.
- When the run is still active in the same process, attach first uses the broadcaster's bounded recent-frame buffer. A cursor inside that buffer is served from memory and then continues with the live tail, without waiting for the log to commit. An older cursor replays the log up to the oldest buffered event, then continues from the buffer and tail. Frames at or before the handoff boundary are dropped by source sequence, so the handover has neither a gap nor a duplicate frame, and text arrives per frame instead of per commit.
- When no live tail is available — a different instance, a restarted process, or the run already finished — attach follows the session log by polling. This is slower, but always correct.
- If the run already ended, it drains the remaining events and finishes with `RUN_FINISHED` or `RUN_ERROR`.
- Text and tool output after the cursor become `TEXT_MESSAGE_*`, `REASONING_MESSAGE_*`, `TOOL_CALL_*`, and `RUN_*` events.
- A tool call that is already part of the application history is not restarted: a later result becomes `TOOL_CALL_RESULT` for the existing tool-call id.
- `RUN_STARTED` is replayed from the committed `run/start` event, so a cursor past it does not announce a new run.

The cursor is a native `SessionEvent.seq()`, not an AG-UI field and not interchangeable with another session or the Service SSE cursor.

### Cursor must be a presentation boundary

Use the last committed event whose effects are fully present in the application history. Do not use `head().seq()` if the run has in-flight text or tool arguments; attach would then skip them. Do not use a cursor from the middle of a message. With this boundary, attach needs no snapshot and no list of known ids.

### Run status is optional

A status query is useful for choosing between history-only and attach, but attach itself does not require the run to be active. If the run ends between the history query and attach, attach replays the remaining events and terminates with `RUN_FINISHED` or `RUN_ERROR`. If the cursor is already past the terminal event, attach finishes immediately and the final status comes from the application history.

### Limitations

- Completed turns and HITL are application-level history/state; they are not part of this recovery contract.
- Without a live tail the recovery path is poll-based, so commits are delivered in batches (at the recorder flush granularity). The in-process recent-frame buffer makes recent handovers immediate, but it is bounded and process-local; a cursor older than the buffer falls back to the log.
- The run must survive the disconnected browser request. Use `AgentSession` or a long-lived agent with `interrupt-on-disconnect=false`.
- Server restarts require `AgentSession`/durable scheduling; an in-memory subscription does not survive.
- Multi-instance deployments need the same `SessionLogStore` and namespace.
- The cursor must belong to the same session and must not exceed the current head.
- Partial model output is recovered by replaying `model/chunk` events, not by a snapshot.

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

Each payload includes at least `source` and `type` (plus type-specific fields such as `delta` or `toolCallId`). Forwarded subagent frames never carry the parent's replay cursor: their child journal sequence is not comparable with the parent log, so they are delivered by the live tail without an SSE `id`.

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
| `userId` | Resolver-provided id, else the request's first-class userId (see below) |
| `RunAgentInput.class` | Full `RunAgentInput` |
| `agui.threadId` | `RunAgentInput.threadId` |
| `agui.runId` | `RunAgentInput.runId` |
| `agui.messages` | `RunAgentInput.messages` |
| `agui.tools` | `RunAgentInput.tools` |
| `agui.context` | `RunAgentInput.context` |
| `agui.state` | `RunAgentInput.state` |
| `agui.forwardedProps` | `RunAgentInput.forwardedProps` |
| `agui.resume` | `RunAgentInput.resume` |

`sessionId` comes from `threadId`. With the same user, thread and persistent storage configuration, a new instance can continue the conversation; different threads use separate session identities.

`userId` is not part of the AG-UI protocol payload. Hosts provide it through two entry points, merged in priority order:

1. `AguiRuntimeContextResolver` — the deployer's decision (e.g. derived from the authenticated principal). Takes precedence.
2. `AguiRuntimeContextRequest.getUserId()` — a caller assertion set programmatically on the request. Adopted only when the resolver provided no id.

Hosts driving `AguiRequestProcessor` directly (custom transports) pass the authenticated id as a first-class field instead of inventing a resolver convention:

```java
AguiRuntimeContextRequest<?> request = AguiRuntimeContextRequest.builder()
    .input(input)
    .userId(authenticatedUserId)
    .build();
```

When both entry points provide an id and they differ, a WARN is logged and the resolver value wins — a caller assertion must not override a server-verified identity. Hosts that provide no user id run anonymously. The official starter does not know the host identity model, so starter users should expose an `AguiRuntimeContextResolver` bean (see Spring Boot Integration below).

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

`forwardedProps` comes from the client request body and is suitable for UI options or frontend context. Do not treat it as a trusted identity source; server-side user identity should come from authentication, injected via a server-side resolver or the request's first-class userId.

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

## Example Project

See the complete example at [agentscope-examples/documentation](https://github.com/agentscope-ai/agentscope-java/tree/main/agentscope-examples/documentation):

```bash
export DASHSCOPE_API_KEY=your-key
cd agentscope-examples/documentation
mvn spring-boot:run -Dspring-boot.run.mainClass=io.agentscope.examples.documentation2.agui.AguiExampleApplication
```

Visit http://localhost:8080 after startup. The example demonstrates multi-agent routing, custom converters, custom enrichers, token usage, and HITL interrupts.
