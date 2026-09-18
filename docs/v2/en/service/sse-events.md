---
title: "SSE format and task feedback"
---

<Note>
This is preview documentation. The official release is not yet available.
</Note>

After submitting work through an Endpoint, subscribe to the returned `eventsUrl` and query `statusUrl` for results. Follow [Endpoint integration](/v2/en/service/endpoints) to publish and submit requests. Console users handle notifications, approvals and acceptance in [Inbox](/v2/en/service/inbox).

## Submit, subscribe and query

1. Submit a Conversation or Job request. Save `invocationId`, `eventsUrl` and `statusUrl`; Conversation also returns identifiers such as `conversationId` and `turnId`.
2. GET `eventsUrl` with the same credential and `Accept: text/event-stream`.
3. Parse SSE frames, persist processed cursors and update the UI according to event type.
4. Query `statusUrl` when the stream ends or disconnects to determine the invocation's status and result.

`202 Accepted` acknowledges submission. SSE transports events; receiving an event or observing a closed connection does not by itself prove successful completion.

## SSE frames

Each business event contains `id`, `event` and JSON `data`, terminated by a blank line. This Conversation example uses demonstration identifiers, timestamps and content:

```text
id: 7
event: assistant.message
data: {"id":145,"sessionFk":"11111111-1111-4111-8111-111111111111","seq":7,"eventType":"assistant.message","role":"assistant","content":"The action list is ready.","occurredAt":"2026-09-10T09:00:00Z"}

```

| Field | Handling |
| --- | --- |
| SSE `id` | Ordered stream cursor for resumption, not the invocation ID |
| SSE `event` | Event type; dispatch by type and tolerate unknown types |
| SSE `data` | A JSON event object; parse Conversation and Job structures separately |
| Blank line | End of a frame; a network chunk need not contain exactly one complete frame |

While waiting for events, the service may send a `: heartbeat` comment. Ignore it rather than parsing it as JSON or treating it as work progress.

```text
: heartbeat

```

The transport uses standard SSE framing. Business events are Service Session or orchestration events, so do not assume a model vendor's token-delta payload or a fixed `[DONE]` marker.

## Conversation and Job payloads

| | Conversation | Job |
| --- | --- | --- |
| Source | Runtime Session events | Run orchestration events |
| Field corresponding to SSE `id` | `seq` | `sequence` |
| Type field | `eventType` | `type` |
| Correlation | `sessionFk`; runtimes may supply `frameworkMeta` | `runId`, with optional `nodeId`, `agentTaskId`, `attemptId` |
| Common content | `role`, `content`, `toolName`, `toolInput`, `toolOutput` | `actor`, `payload`, `occurredAt` |
| Example types | `assistant.message`, `turn.completed`, `turn.failed` | `run.started`, `node.succeeded`, `node.failed` |

Fields and event types depend on the execution path; providers need not emit the same types or granularity. Optional fields may be absent. Conversation JSON `id` identifies a stored record; resume using SSE `id` / `seq`. A Job JSON `id` likewise cannot replace `sequence`.

Example Job event fields:

```text
id: 1
event: run.started
data: {"id":"22222222-2222-4222-8222-222222222222","runId":"33333333-3333-4333-8333-333333333333","tenant":"default","namespace":"default","sequence":1,"type":"run.started","actor":{"type":"system","ref":"endpoint:example"},"occurredAt":"2026-09-10T09:00:00Z"}

```

Conversation events are read by Session cursor. The returned URL's `invocationId` associates stream termination with the current invocation; it does not filter Session history to that turn. Starting at cursor 0 can replay earlier events. Preserve processed cursors and use available correlation such as `frameworkMeta.turnId` to distinguish turns rather than displaying old output as a new reply.

## Subscribe and resume

Set `BASE_URL` to the Gateway origin, `ENDPOINT_TOKEN` to the credential used for submission, and `EVENTS_PATH` to the full returned relative `eventsUrl`, including query parameters:

```bash
curl -N --fail-with-body "$BASE_URL$EVENTS_PATH" \
  -H "X-API-Key: $ENDPOINT_TOKEN" \
  -H 'Accept: text/event-stream'
```

For `platform` authentication, replace the authentication header with `Authorization: Bearer $ENDPOINT_TOKEN`. Use an absolute returned URL directly instead of prefixing BASE_URL.

Save the SSE `id` after successfully processing a frame. Query status after disconnection, then resubscribe if events are still needed. Use the same URL and credential; set `LAST_EVENT_ID` to the last processed cursor:

```bash
curl -N --fail-with-body "$BASE_URL$EVENTS_PATH" \
  -H "X-API-Key: $ENDPOINT_TOKEN" \
  -H 'Accept: text/event-stream' \
  -H "Last-Event-ID: $LAST_EVENT_ID"
```

Both interfaces also accept an `after` query parameter. When both are present, they use the greater valid value and read subsequent events. Scope cursors to the corresponding Session or Run; never reuse them across unrelated streams. A client can disconnect after processing but before saving its cursor, so deduplicate by stream identity and SSE ID to avoid repeated notifications or business actions.

Resubscription does not resubmit work. Use the original Idempotency-Key when retrying submission. Resolve authentication or authorization errors before reconnecting after 401/403. Proxies must forward events promptly, disable event-stream buffering and allow sufficiently long read timeouts.

## Retrieve results and files

Set `STATUS_PATH` to the submission response's `statusUrl`:

```bash
curl --fail-with-body "$BASE_URL$STATUS_PATH" \
  -H "X-API-Key: $ENDPOINT_TOKEN"
```

- **Conversation**: the response contains `conversation` and `turns`. Match a returned turn's `id` to the submitted `invocationId` and inspect its status and error. Session events provide reply content.
- **Job**: inspect `invocation.status`. Read `invocation.result` after `completed`, or `errorCode` and `errorMessage` on failure. The response may also include `run` and `issue` summaries.
- **Deliverable files**: GET `/invoke/v1/jobs/{invocationId}/artifacts`, then download using the returned `downloadUrl` and the same credential.

`accepted`, `dispatching`, `running` and `waiting` are nonterminal. `completed` indicates invocation completion; `failed`, `cancelled` and `timed_out` are unsuccessful terminal outcomes. One successful node does not complete a Run. Check Job results against the published output schema and business criteria, including whether partial success is sufficient.

Handle human approvals or deliverable acceptance in [console Inbox](/v2/en/service/inbox) according to work policy. Reading SSE does not approve operations or accept deliverables.

Use a Job from the [fulfillment case](/v2/en/service/cases/order-fulfillment) to practice progress subscription, cursor persistence, and final-result queries. Resume the original invocation with its own cursor.
