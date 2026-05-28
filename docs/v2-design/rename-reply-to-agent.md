# Rename: "Reply" → "Agent" in Event/Middleware System

## Motivation

Python 2.0 uses "reply" as the agent invocation concept name. In Java 2.0, the top-level concept should be called "agent" (e.g., `AgentStartEvent`, `MiddlewareBase.onAgent`) to better represent the semantic meaning.

## Scope

### Renamed

| Before | After |
|--------|-------|
| `ReplyStartEvent` | `AgentStartEvent` |
| `ReplyEndEvent` | `AgentEndEvent` |
| `ReplyInput` | `AgentInput` |
| `MiddlewareBase.onReply(...)` | `MiddlewareBase.onAgent(...)` |
| `AgentEventType.REPLY_START` | `AgentEventType.AGENT_START` |
| `AgentEventType.REPLY_END` | `AgentEventType.AGENT_END` |
| `ReActAgent.replyImpl()` | `ReActAgent.agentImpl()` |
| `ReActAgent.coreReply()` | `ReActAgent.coreAgent()` |
| `ReActAgent.resumeReply()` | `ReActAgent.resumeAgent()` |

### Not renamed (intentional)

| Item | Reason |
|------|--------|
| `replyId` field (25 event classes) | Wire format (JSON `@JsonProperty("replyId")`); 219 references; purely a correlation ID, not a user-visible concept name |

## JSON Backward Compatibility

Old serialized values still deserialize correctly:

```java
@JsonAlias({"RUN_STARTED", "REPLY_START"})
AGENT_START("AGENT_START"),

@JsonAlias({"RUN_FINISHED", "REPLY_END"})
AGENT_END("AGENT_END"),
```

The `fromValue()` switch also handles legacy strings explicitly.

Serialization now emits `"AGENT_START"` / `"AGENT_END"` as canonical form.

## Files Changed

| File | Change |
|------|--------|
| `core/event/AgentStartEvent.java` | Renamed from `ReplyStartEvent.java` |
| `core/event/AgentEndEvent.java` | Renamed from `ReplyEndEvent.java` |
| `core/event/AgentEventType.java` | Enum values renamed + aliases added |
| `core/event/AgentEvent.java` | `@JsonSubTypes` mapping updated |
| `core/middleware/AgentInput.java` | Renamed from `ReplyInput.java` |
| `core/middleware/MiddlewareBase.java` | `onReply` → `onAgent` |
| `core/ReActAgent.java` | Internal methods + references |
| `core/tracing/OtelTracingMiddleware.java` | References updated |
| `core/legacy/hook/Hook.java` | Javadoc reference updated |
| Test files (4) | Import + usage updates |
