---
title: "Session history and live events"
description: "Open and buffer a live stream, read history, then deduplicate stable Event IDs."
zh_link: /v2/zh/service/sse-events
---

This page applies to the Managed Draft 3 first release. See [Session Event API](/v2/en/service/claude-session-api).
Only ordinary live SSE is enabled; reliable resume is a separate future capability.

GET /v1/sessions/{session}/events/stream follows events after its connection boundary. The Service
captures a durable position before flushing the connected comment, and reads committed facts after it.
Notifications are not the only source of truth.

```text
event: agent.message
data: {"id":"evt_answer","type":"agent.message","content":[{"type":"text","text":"Done."}],"processed_at":"2026-10-19T07:01:36Z"}

: keepalive
```

The first release rejects after, Last-Event-ID, preview and event_deltas. The official SDK beta=true
transport flag is accepted. A Turn ending keeps the stream open; disconnecting does not cancel work.
AgentScope-Extensions: turn-inputs-v1 adds platform fields and Turn/Input notifications. Standard-only
streams contain standard types. Checkpoints, private thinking, leases and credentials remain private.

Recovery order:

1. Open a new live stream and buffer its events.
2. Read /events?limit=100&order=asc, following next_page.
3. Apply history and buffered events, deduplicating stable Event IDs.
4. Continue consuming the stream; repeat after another disconnect.

Reading history before opening a future-only stream leaves a gap. Pending admitted inputs are visible
with processed_at=null; processing updates the same logical Event ID. History freezes its upper bound
and projection versions, with types[] and processed_at time filters.

For results without observation, poll the Turn to terminal and page /turns/{turn}/output. This requires
no SSE, snapshot or durable capability. Terminal without output returns empty data and its actual status.
Handshake errors use HTTP JSON. A transport failure after opening disconnects the observer and does not
fail execution. Read permission is rechecked periodically. Keepalive is not completion or a resume cursor.

Reliable delivery, snapshots, preview and the new Managed Webhook protocol remain disabled. Other
backends retain their own observation contracts; their cursors are not Managed history pages.

### Managed tool output and operation progress

Use `agent.tool_use`, `agent.mcp_tool_use` and their result events to display tool activity. Merge updates by stable Event identity and read the targeted Turn/Input resources for execution and settlement. Tool output does not by itself prove that a Turn completed; final output is queried independently. Private checkpoints and execution leases are not public progress fields.

## Webhooks

The new Managed Draft 3 Webhook protocol remains disabled; `/capabilities` is authoritative and earlier native `/v1/sessions/{id}/webhooks` registration examples do not apply. Observe the Session event stream or query Turn/output resources instead. Hosted and External backends retain their separate `/api/v1/agent-sessions/{id}/webhooks` contract and existing delivery behavior. Automation inbound Webhooks remain a separate trigger mechanism; see [Automation](/v2/en/service/automation).

{/* Preserve earlier anchors; current capability limits supersede the old examples. */}
<span id="restore-a-snapshot-before-applying-events"></span>
<span id="interpret-events-and-status"></span>
<span id="pagination-reconnects-and-expiration"></span>
<span id="notify-a-backend-while-users-are-offline"></span>
<span id="managed-runtime-contract"></span>
