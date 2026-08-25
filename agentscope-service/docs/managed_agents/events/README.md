# Session events reference

Canonical event types for AgentScope Builder Managed Sessions.  
Contract: [DATA_PLANE_CONTRACT.md](../DATA_PLANE_CONTRACT.md).

Envelope (persisted):

```jsonc
{
  "id": "evt_...",
  "sessionId": "sess_...",
  "seq": 42,
  "type": "agent.message",
  "payload": { /* type-specific */ },
  "processedAt": 1720000000000,
  "createdAt": 1720000000000
}
```

---

## Inbound (`POST /api/sessions/{id}/events`)

| `type` | Behavior | Support |
|---|---|---|
| `user.message` | Append + run turn | Full |
| `user.interrupt` | Interrupt in-flight turn | Full |
| `user.tool_confirmation` | HITL allow/deny (`tool_use_id` preferred; `toolUseId` accepted) | Full |
| `user.custom_tool_result` | Append + resume after `agent.custom_tool_use` (Brain wired; Worker custom SPI TBD) | Full (Brain) |
| `user.tool_result` | Append + resume for self_hosted / externalized tools | Full |
| `user.define_outcome` | Append outcome record | Skeleton |
| `system.message` | Session-scoped `system` override (next turn); does not write Agent | Full |
| *(unknown)* | **400** `invalid_request_error` / `unknown_event_type` | — |

Batch: all-or-nothing (no partial commit).

---

## Outbound (persisted)

### Agent

| `type` | Payload notes |
|---|---|
| `agent.message` | `text`, `content: [{type:text,text}]` |
| `agent.thinking` | Final thinking (deltas are stream-only) |
| `agent.tool_use` | `id`, `name`, `input` (+ compat `toolCallId` / `toolName`) |
| `agent.tool_result` | `tool_use_id` / `id`, `name`, `state` |
| `agent.mcp_tool_use` / `agent.mcp_tool_result` | Reserved |
| `agent.custom_tool_use` | Reserved |
| `agent.thread_context_compacted` | Reserved |

### Session

| `type` | Notes |
|---|---|
| `session.status_created` | On create |
| `session.status_running` | Turn start |
| `session.status_idle` | Turn complete (`stopReason` optional) |
| `session.status_requires_action` | HITL |
| `session.status_terminated` | Unrecoverable failure |
| `session.status_rescheduled` | Reserved |
| `session.status_archived` | Archive |
| `session.error` | Typed `{ error: { type, code, message, retry_status } }` |
| `session.updated` | e.g. after `system.message` |
| `session.deleted` | Emitted live before wipe |
| `session.interrupted` | Interrupt; may include `targetInstanceId` when pending |
| `session.requires_action` | HITL detail payload |

### Span

| `type` | Notes |
|---|---|
| `span.model_request_start` | Model call begin |
| `span.model_request_end` | Includes `usage` when available |

Removed from public surface: `agent.reasoning`, `agent.tool_use_delta` (persisted), `agent.model_call_*`, `session.agent_start` / `session.agent_end`, `session.interrupt_requested`.

---

## List filters (`GET …/events`)

| Query | Meaning |
|---|---|
| `after` | Only events with `seq > after` |
| `types` | Repeatable; only these event types (Claude `types[]` equivalent) |

## Stream-only (`GET …/events/stream?event_deltas=…`)

Never appear in `GET …/events`.

| `type` | Payload |
|---|---|
| `event_start` | `event_id`, `type` (target persisted type) |
| `event_delta` | `event_id`, `type`, `delta` (plain text fragment) |

Supported `event_deltas` values: `agent.message`, `agent.thinking`, `agent.tool_use` (other values → 400).

Preview `event_id` is a real `evt_*` id reused when the buffered event is persisted, so clients can reconcile typewriter previews with the authoritative row. Tool `input` / `output` are accumulated across harness deltas and written on End (not empty shells).

Deltas are best-effort on the turn-owner JVM; persisted events remain the source of truth.
