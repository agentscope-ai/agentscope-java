# Subagent Streaming

> **Prerequisite**: [Streaming basics](../task/streaming.md) — `stream()` API, `EventType`, `StreamOptions`, SSE integration. This page focuses on `HarnessAgent`'s **child-agent event forwarding** mechanism.

When a parent `HarnessAgent.stream()` triggers `agent_spawn` or `agent_send`, **all intermediate events** from the child agent are injected into the parent `Flux<Event>` in real time, tagged with an `EventSource` that identifies their origin — no extra configuration needed.

---

## How It Works

```
AgentBase.createEventStream()
  │
  ├─ Creates FluxSink<Event>
  ├─ Constructs SubagentEventBus (impl = sink::next)
  └─ Injects bus into Reactor Context
        │
        ▼
  ReActAgent reasoning loop (runs inside Reactor Context)
        │
        ▼  acting phase calls agent_spawn
  AgentSpawnTool.execLocalSync()
        │
        ├─ Reads SubagentEventBus from Reactor Context
        ├─ Calls DefaultAgentManager.invokeAgentStream()
        │       │  child HarnessAgent.stream()
        │       │    each child Event ──map(withSource)──▶ tagged with EventSource
        │       └─ Flux<Event> flows back to execLocalSync
        │
        └─ doOnNext(bus::emit) ──▶ each child Event pushed into parent FluxSink live
```

**Key constraint**: the bus only exists in `stream()` mode. When using `call()`, no bus is present and `agent_spawn` falls back to the blocking `invokeAgent` path with no behaviour change.

---

## Event Timeline

```
Caller
  └─ parent.stream()
        │
        ├─ REASONING(parent, chunk×N)   ← parent reasoning turn 1 (with tool_use)
        │
        │  [agent_spawn "researcher" starts]
        ├─ REASONING(child, chunk×M)    ← child reasoning (forwarded live, has EventSource)
        ├─ TOOL_RESULT(child, ...)      ← child tool result (forwarded live, if any)
        ├─ REASONING(child, last)       ← child final reasoning (forwarded live)
        ├─ AGENT_RESULT(child, last)    ← child final reply (forwarded live)
        │  [agent_spawn ends; child result returned as TOOL_RESULT to parent]
        │
        ├─ TOOL_RESULT(parent, ...)     ← parent receives child result
        ├─ REASONING(parent, chunk×K)   ← parent reasoning turn 2
        └─ AGENT_RESULT(parent, last)   ← parent final reply
```

Parent events have `source == null`; child events have `source != null`.

---

## Identifying the Source with EventSource

```java
Flux<Event> events = parent.stream(msgs, StreamOptions.defaults(), ctx);

events.subscribe(event -> {
    EventSource src = event.getSource();
    if (src == null) {
        // parent agent's own event
        System.out.printf("[parent][%s] %s%n",
                event.getType(), event.getMessage().getTextContent());
    } else {
        // child (or grandchild) agent event
        System.out.printf("[%s|depth=%d|path=%s][%s] %s%n",
                src.getAgentId(), src.getDepth(), src.getPath(),
                event.getType(), event.getMessage().getTextContent());
    }
});
```

### EventSource Field Reference

| Field | Meaning | Example |
|-------|---------|---------|
| `agentKey` | Runtime instance handle; pass to `agent_send` | `agent:researcher:550e8400-…` |
| `agentId` | Child agent type ID (matches `subagents/<id>.md` filename) | `researcher` |
| `agentName` | Child agent display name (may be null) | `ResearcherAgent` |
| `sessionId` | Unique session ID for this child invocation | `sub-a1b2c3d4-…` |
| `parentSessionId` | Session ID of the parent agent | `sess-main-001` |
| `depth` | Nesting depth: direct child = 1, grandchild = 2, … | `1` |
| `path` | Slash-separated call hierarchy | `sess-main-001/researcher` |
| `taskId` | Reserved; `null` today (reserved for async streaming) | `null` |

**Path convention**: `<parentSessionId>/<agentId>`. For deeper nesting it extends naturally: `sess-001/planner/executor`.

---

## Multi-level Nesting (Grandchild Agents)

A child `HarnessAgent` injects its **own** bus at its `stream()` entry point. Grandchild events are first captured by the child bus (depth+1), then flow out through the child `Flux<Event>`, where `AgentSpawnTool` in the grandparent forwards them again to the grandparent bus. The `path` field accumulates depth automatically.

```
parent.stream()
  └─ child.stream()             depth=1, path="sess/planner"
        └─ grandchild.stream()  depth=2, path="sess/planner/executor"
```

Filter by depth or path prefix to target any level:

```java
// Only depth-1 child REASONING events
events.filter(e -> e.getSource() != null
               && e.getSource().getDepth() == 1
               && e.getType() == EventType.REASONING)
      .subscribe(...);

// All events from any agent in the "executor" path segment
events.filter(e -> e.getSource() != null
               && e.getSource().getPath().contains("executor"))
      .subscribe(...);
```

---

## Common Consumption Patterns

### 1. Real-time UI Rendering per Agent

Route events to separate UI panels based on source:

```java
events.groupBy(e -> e.getSource() == null ? "parent" : e.getSource().getAgentId())
      .flatMap(group -> group.doOnNext(e -> renderToPanel(group.key(), e)))
      .subscribe();
```

### 2. Await Child Agent's Final Reply

If you only care about the child's result text (not intermediate events):

```java
String childReply = events
        .filter(e -> e.getSource() != null
                  && "researcher".equals(e.getSource().getAgentId())
                  && e.getType() == EventType.AGENT_RESULT
                  && e.isLast())
        .map(e -> e.getMessage().getTextContent())
        .blockFirst();
```

### 3. Collect and Group by Agent

```java
Map<String, List<Event>> byAgent = events.collectList().block()
        .stream()
        .collect(Collectors.groupingBy(e ->
                e.getSource() == null ? "parent" : e.getSource().getAgentId()));
```

### 4. SSE with Source Metadata

Forward child-agent origin to the browser:

```java
@GetMapping(value = "/chat", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
public Flux<ServerSentEvent<String>> chat(@RequestParam String message,
                                          @RequestParam String sessionId) {
    RuntimeContext ctx = RuntimeContext.builder().sessionId(sessionId).build();
    return agent.stream(
                    List.of(Msg.builder().role(MsgRole.USER).textContent(message).build()),
                    StreamOptions.defaults(), ctx)
            .map(event -> {
                Map<String, Object> payload = new LinkedHashMap<>();
                payload.put("type", event.getType());
                payload.put("text", event.getMessage().getTextContent());
                payload.put("last", event.isLast());
                if (event.getSource() != null) {
                    payload.put("agentId", event.getSource().getAgentId());
                    payload.put("depth",   event.getSource().getDepth());
                    payload.put("path",    event.getSource().getPath());
                }
                return ServerSentEvent.<String>builder()
                        .data(objectMapper.writeValueAsString(payload))
                        .build();
            });
}
```

---

## Error Handling

When a child agent throws internally, the framework **catches the exception and writes it as a `TOOL_RESULT` error string**. The `onError` signal is not propagated to the parent `Flux`, so the parent stream continues normally.

For fatal errors in the parent stream itself, standard Reactor semantics apply:

```java
events.onErrorResume(e -> {
    log.error("Parent stream error", e);
    return Flux.empty();
}).subscribe(...);
```

---

## Scope Boundaries

| Scenario | Behaviour |
|----------|-----------|
| `stream()` + sync local subagent (`timeout_seconds > 0`) | ✔ Child events forwarded live with `EventSource` |
| `call()` mode (non-streaming) | ✔ Normal blocking invocation; child result returned as `tool_result` string; no event forwarding |
| `timeout_seconds=0` async background task | ✗ Streaming not yet supported (future extension point) |
| Remote subagent (Agent Protocol) | ✗ Streaming not yet supported (future extension point) |
| Multi-level nesting (grandchild) | ✔ Path/depth accumulate automatically; `FluxSink.next` is thread-safe |

---

## Related Documents

- [Streaming Basics](../task/streaming.md) — `stream()` API, `EventType`, full `StreamOptions` reference
- [Subagent](./subagent.md) — Subagent declarations, `agent_spawn` / `agent_send` parameters
- [Architecture](./architecture.md) — `SubagentEventBus`, Reactor Context injection, and `StreamingHook` lifecycle
