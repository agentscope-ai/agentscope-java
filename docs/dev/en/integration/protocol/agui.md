---
title: AG-UI
zh_link: /dev/zh/integration/protocol/agui
---

## Compatibility Notes

`agentscope-extensions-agui` converts AgentScope v2 `AgentEvent` streams into [AG-UI Protocol](https://github.com/ag-ui-protocol/ag-ui) events so front-end UIs can render an agent run in real time, including text, reasoning, tool calls, state, custom events, token usage, and HITL interrupts.

`RUN_ERROR` and `RUN_FINISHED` are mutually exclusive terminal events. Set `emitRunFinishedAfterError=true` only if you still need the legacy `RUN_ERROR` + `RUN_FINISHED` sequence.

`AguiMessage.content` is represented as typed message content. For text-only code paths, use `getTextContent()`.

Multimodal input supports text, image, audio, and video. Document input is not supported yet: the adapter rejects it with `RUN_ERROR` and code `INVALID_INPUT_ERROR`, without including the document source or metadata in the error message.

This protection applies only to the `RUN_ERROR` message, not to the entire event stream. The preceding `RUN_STARTED.input` retains the original request, including rejected document content. Consumers that log or forward events must redact sensitive input themselves.

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

Configure a shared `HarnessAgent.Builder agentBuilder` as in the [Quickstart](/dev/en/docs/quickstart). Build an Agent for each request and close it when the SSE stream ends or is cancelled:

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

`sessionId` comes from `threadId`. With the same user, thread and persistent storage configuration, a new instance can continue the conversation; different threads use separate session identities.

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

## Message Queue For Busy Threads

By default a thread runs one agent turn at a time. When a client sends another message while the thread is busy, or while the thread is still waiting for a HITL confirmation, the run is rejected with `AGUI_INTERRUPT_CONTRACT_ERROR`. The optional message queue accepts those messages on the server instead:

```yaml
agentscope:
  agui:
    queue:
      enabled: true               # default false
      capacity: 16                # queued messages per thread
      idempotency-retention: 64   # message ids remembered for deduplication, must be >= capacity
```

The queue is disabled by default. While it is disabled the endpoints below are not registered and the run endpoint behaves exactly as before. The status code an unregistered path returns is whatever the surrounding application produces for an unmapped URL, so a client must not depend on a specific one.

| Method | Path | Purpose |
|---|---|---|
| `POST` | `{prefix}/queue/{threadId}` | Accept a message; returns `status`, `messageId`, `position` and `state` |
| `GET` | `{prefix}/queue/{threadId}` | Snapshot of queued items plus `active` (a run is executing) and `awaitingConfirmation` (an interrupt is unresolved) |
| `DELETE` | `{prefix}/queue/{threadId}/{messageId}` | Withdraw a queued message (`204`; `404` when the id is unknown, was already withdrawn, or was forgotten by the deduplication ledger; `409` when already claimed) |
| `POST` | `{prefix}/queue/{threadId}/claim` | Claim the oldest message while the thread is idle; returns `messageId` and the `input` to replay |

The queue never starts a run by itself. A client claims a message and replays the returned `input` object on the regular run endpoint, so streaming, interrupts and disconnect handling stay unchanged. Accepting a message does not require the thread to be busy: a message submitted while the thread is idle simply waits in the queue until it is claimed. `{prefix}` is `agentscope.agui.path-prefix` (default `/agui`). The accept endpoint requires `Content-Type: application/json` exactly like the run endpoint; without it Spring rejects the request with `415` before the body is parsed.

The message id is the `id` of the trailing message of the request body. A retry with the same body therefore returns `DUPLICATE` instead of queueing twice, while reusing an id with a different trailing message is rejected with `409`. The deduplication ledger only keeps the last `idempotency-retention` ids per thread; once an id has been evicted, a retry of the same body is accepted as a new message instead of returning `DUPLICATE`. An id whose message is still queued is never evicted, so a retry of a queued message always finds it; eviction skips those ids and can therefore drop a claimed or withdrawn id first. Only that trailing message is compared: `runId`, earlier messages, `tools`, `context`, `state` and `forwardedProps` may differ and still count as a retry, in which case the originally queued body is replayed. `position` is `-1` once the message has left the queue, and `state` is `QUEUED` while it waits, `CLAIMED` after a claim, `WITHDRAWN` after a withdrawal, and `CONFLICT` or `FULL` when the submission was rejected. A body must contain at least one message and the trailing message must carry a non-blank `id`. A body that answers interrupts (`resume`) is rejected with `400`: a thread that waits for a confirmation never becomes idle, so answers always belong on the run endpoint. Withdrawing a message that is still queued keeps its id in the ledger as `WITHDRAWN` for the retention window, so a retry of the original submission that arrives after the withdrawal response was lost is acknowledged as `DUPLICATE` instead of being queued and executed again; reusing the id with a different trailing message is rejected while it is retained.

A claim delivers at most one message per idle window: after a successful claim, further claims return `204` until a claim observes the thread running, or the thread's most recently started run is the very run id this delivery handed out, or five seconds have passed. The run-id rule means a client that does not poll `claim` during the replay still gets the next message as soon as the replayed run has finished and the thread is idle again. A run under any other id — another client's direct request, or a resume answer under its own id — does not open the window; a resume that reuses the delivered id is that replay. The five seconds remain the fallback whenever no claim observes the match: a delivery that was never replayed, or one whose evidence a later run overwrote first. `204` also means that the thread is busy or that nothing is queued, so a client polls it. Claim responses are encoded with AgentScope's JSON codec, the same codec that parses requests, so replay the `input` object exactly as received. The claimed `input` carries a fresh `runId`: a body queued while a run was active usually repeats that run's id, and replaying it would make two runs share one identifier, which clients cannot tell apart and which gateways that de-duplicate requests on the run id reject. Everything else is returned unchanged, and events of the replayed run carry the fresh `runId`, so correlate them with it. Treat the claimed `input` as a single-use replay credential: AgentScope does not de-duplicate on the run id, so a second replay would start the message again, and a gateway that de-duplicates on the run id rejects the second replay with its duplicate-request response. Replay it exactly once and never re-post a claimed `input` as a retry — once handed out, the message is consumed, and the next claim returns the next message.

Notes:

- Queues live in process memory. A restart drops queued messages and the deduplication ledger, and two application instances share neither queues nor run state. Run a single instance, or route a thread to the same instance. The registry keeps one queue per thread that has submitted a message and never evicts it, the processor likewise remembers the run id each thread last started, and each queued message holds the full request body, so memory grows linearly with the number of such threads.
- Claiming removes the message from the queue. If a client crashes after claiming but before replaying it, that message is lost. The same applies when the claim response is lost on the network and the client retries: the retry is not a replay, and the next claim returns the next message. This is intentional: a long run must never be claimed and executed twice. The queue's guarantee is delivery once; replaying the claimed `input` twice starts the message twice, because the run endpoint does not de-duplicate on the run id, so replay it exactly once.
- Replay the claimed message promptly. If another client starts a run on the same thread first, the run endpoint rejects the replay with `AGUI_INTERRUPT_CONTRACT_ERROR`, and the claimed message can neither be requeued nor withdrawn, so it is lost. Keep a single claimant per thread.
- `active=false` only means the AGUI processor has no active run. It does not mean the caller's own persistence, audit, or asynchronous consumers have finished. If the caller has its own terminal-write or concurrency gate, it should finish that work before replaying the claimed `input`; the queue does not wait for the caller's downstream writes.
- With client-side memory (`agentscope.agui.server-side-memory=false`, the default), the claimed `input` is the transcript as it was when the message was accepted. If the thread completed further turns before the claim, merge the queued message into your current transcript instead of replaying the snapshot verbatim; with server-side memory no merge is needed.
- If a run ends with an error while a confirmation is still open, the thread stays blocked: the queue keeps accepting new messages but claims nothing, and no run without `resume` is accepted, until the same interrupt is answered again or the client moves to a new thread id. This is the upstream contract for interrupted runs; a downstream filter that silently resolves open interrupts changes run semantics and is not supported.
- `capacity` bounds each thread; a full queue returns `429`. A body that is not valid AG-UI JSON, has no messages, has a message element that is `null`, has a trailing message without a non-blank `id`, carries `resume`, or names a different `threadId` than the path returns `400`. This `400` mapping applies to requests that reach the accept endpoint; components earlier in the host application's chain (for example a filter that parses the body for its own logging) can still fail with their own error responses (for example a `500`) before the endpoint is reached.
- The endpoints inherit the authentication model of the run endpoint: the queue does not authenticate clients itself, and a claim returns the full conversation content of the thread. Protect `{prefix}/queue/**` at the gateway, exactly like `{prefix}/run`.
- The queue stores the request body only. Agent selection from the URL path or the agent-id header is not part of the body and is therefore not preserved on replay; put the agent id in `forwardedProps` if the replay must target a specific agent.

## Example Project

See the complete example at [agentscope-examples/documentation](https://github.com/agentscope-ai/agentscope-java/tree/main/agentscope-examples/documentation):

```bash
export DASHSCOPE_API_KEY=your-key
cd agentscope-examples/documentation
mvn spring-boot:run -Dspring-boot.run.mainClass=io.agentscope.examples.documentation2.agui.AguiExampleApplication
```

Visit http://localhost:8080 after startup. The example demonstrates multi-agent routing, custom converters, custom enrichers, token usage, and HITL interrupts.
