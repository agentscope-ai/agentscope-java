# Streaming

`Agent.stream()` returns a reactive `Flux<Event>` that lets callers observe every reasoning step, tool-call result, and final reply **in real time**, rather than waiting for `call()` to return a single `Msg`.

---

## Basic Usage

```java
// Works with both ReActAgent and HarnessAgent
Flux<Event> events = agent.stream(
        List.of(Msg.builder().role(MsgRole.USER).textContent("Analyze this log").build()),
        StreamOptions.defaults());

// Subscribe and print each event
events.subscribe(event ->
    System.out.printf("[%s|last=%s] %s%n",
            event.getType(), event.isLast(),
            event.getMessage().getTextContent()));

// Blocking collect (for testing / batch pipelines)
List<Event> all = events.collectList().block();
```

With `RuntimeContext` (HarnessAgent):

```java
RuntimeContext ctx = RuntimeContext.builder()
        .sessionId("my-session").userId("alice").build();

Flux<Event> events = agent.stream(msgs, StreamOptions.defaults(), ctx);
```

`StreamOptions.defaults()` enables **all event types** in **incremental mode**.

---

## Event Types (EventType)

| Type | When | Typical content |
|------|------|----------------|
| `REASONING` | Each reasoning turn (may have multiple chunks) | Model text / thinking chain / tool_use calls |
| `TOOL_RESULT` | After each tool execution | `ToolResultBlock` (tool name, id, output) |
| `HINT` | After RAG / memory retrieval | Context text injected into the model |
| `SUMMARY` | When `maxIters` is reached | Iteration summary text |
| `AGENT_RESULT` | Final reply ready | Same as `call()` return value; **not included** in stream by default |
| `ALL` | Placeholder for all the above (except `AGENT_RESULT`) | — |

### Subscribe to specific types only

```java
StreamOptions options = StreamOptions.builder()
        .eventTypes(EventType.REASONING, EventType.TOOL_RESULT)
        .build();
```

### Distinguish chunks from final results

For the same `REASONING` message the model first streams several **incremental chunks**,
then emits a **final event** with the complete text. Use `Event.isLast()`:

```java
events.filter(e -> e.getType() == EventType.REASONING)
      .subscribe(e -> {
          if (e.isLast()) {
              System.out.println("Reasoning done: " + e.getMessage().getTextContent());
          } else {
              System.out.print(e.getMessage().getTextContent());  // live delta
          }
      });
```

---

## Incremental vs. Full-text Mode

| `incremental` | Behaviour |
|--------------|-----------|
| `true` (default) | Each chunk contains only the **new text delta**; caller concatenates. |
| `false` | Each chunk carries the **full accumulated text** up to that point — convenient for direct rendering. |

```java
StreamOptions options = StreamOptions.builder()
        .incremental(false)   // recommended for UI rendering
        .build();
```

---

## Reasoning / Summary Content Filtering

Some models emit both **intermediate delta chunks** and a **final consolidated result**
inside the same `REASONING` event group. Control them independently:

```java
StreamOptions options = StreamOptions.builder()
        .eventTypes(EventType.REASONING)
        .includeReasoningChunk(false)   // skip intermediate deltas
        .includeReasoningResult(true)   // keep only the final reasoning result
        .build();
```

Similarly for summaries:

```java
StreamOptions options = StreamOptions.builder()
        .eventTypes(EventType.SUMMARY)
        .includeSummaryChunk(false)
        .includeSummaryResult(true)
        .build();
```

---

## Error Handling

`stream()` follows standard Reactor error semantics; use `onErrorResume` to handle:

```java
events.onErrorResume(e -> {
    log.error("Stream error", e);
    return Flux.empty();
}).subscribe(...);
```

When a tool execution fails, the framework converts the exception to a `TOOL_RESULT` error string. The `onError` signal is **not** propagated to the parent stream, so the overall stream is not terminated prematurely.

---

## Integration with Spring WebFlux / SSE

`Flux<Event>` bridges directly into Server-Sent Events:

```java
@GetMapping(value = "/chat", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
public Flux<ServerSentEvent<String>> chat(@RequestParam String message) {
    return agent.stream(
                    List.of(Msg.builder().role(MsgRole.USER).textContent(message).build()),
                    StreamOptions.defaults())
            .map(event -> ServerSentEvent.<String>builder()
                    .event(event.getType().name().toLowerCase())
                    .data(event.getMessage().getTextContent())
                    .build());
}
```

When using `HarnessAgent` and you need to forward child-agent source metadata to the frontend,
serialize `event.getSource()` as well — see [Harness Subagent Streaming](../harness/streaming.md).

---

## StreamOptions Reference

```java
StreamOptions options = StreamOptions.builder()
        // Event types to receive (default: ALL, excluding AGENT_RESULT)
        .eventTypes(EventType.REASONING, EventType.TOOL_RESULT, EventType.AGENT_RESULT)

        // Incremental mode: true = push deltas (default), false = push full accumulated text
        .incremental(true)

        // Reasoning content filters (both default true)
        .includeReasoningChunk(true)
        .includeReasoningResult(true)

        // Summary content filters (both default true)
        .includeSummaryChunk(true)
        .includeSummaryResult(true)

        .build();
```

---

## Related Documents

- [Hook](./hook.md) — Insert custom logic at reasoning / tool-call lifecycle points
- [Model](./model.md) — Underlying streaming model configuration
- [Harness Subagent Streaming](../harness/streaming.md) — Child-agent event forwarding and `EventSource` fields when using `HarnessAgent`
