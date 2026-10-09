---
title: "SSE and event replay"
description: "Observe tasks with snapshots and SSE, recover progress after disconnection, and receive backend Webhook notifications."
zh_link: /v2/zh/service/sse-events
---

After submitting a task, an application needs to show its background progress to the user. The Session API preserves snapshots and an event log: a snapshot restores work that has already happened, and subsequent events update messages, tool calls, and pending actions. SSE delivers those events continuously, so an application can follow the same task after a page refresh or network interruption. Closing the connection does not cancel execution.

Start with [Integrate applications with the Session API](/v2/en/service/service-api) to create a Session and submit a Turn. The examples below reuse its `SESSION_URL` and application credential. If your backend needs notifications while users are offline, go to the [Webhook section](#webhooks). Webhook notifications can be used alongside page-level SSE subscriptions.

## Restore a snapshot before applying events

When opening a page, read the Session `/snapshot`, restore its messages, tools, and actions, then pass its `as_of` as `after` to `/events/stream`. Persist a cursor only after successfully applying the event. Reconnect from the last applied cursor. If local page state is also lost, reload the snapshot first.

This example uses an application credential. An authorized platform Bearer token can perform the same reads.

```bash
SNAPSHOT=$(
  curl -sS --fail-with-body "$SESSION_URL/snapshot" \
    -H "X-API-Key: $AGENTSCOPE_API_KEY"
)
CURSOR=$(jq -er '.as_of' <<< "$SNAPSHOT")
```

```bash
curl -sS --fail-with-body -N -G "$SESSION_URL/events/stream" \
  -H "X-API-Key: $AGENTSCOPE_API_KEY" \
  -H "Accept: text/event-stream" \
  --data-urlencode "after=$CURSOR"
```

For a view of one task, pair `/turns/{turnId}/snapshot` with `/turns/{turnId}/events/stream`. Sessions, Turns, and subagents have separate journals, and a cursor belongs only to its source resource. It is not a timestamp or an event sequence clients should decode. `Last-Event-ID` takes precedence over the query parameter.

<Accordion title="Subscribe to a Turn and reconnect">

To observe one Turn, use this matching pair:

```bash
TURN_URL="$SESSION_URL/turns/$TURN_ID"
TURN_SNAPSHOT=$(
  curl -sS --fail-with-body "$TURN_URL/snapshot" \
    -H "X-API-Key: $AGENTSCOPE_API_KEY"
)
TURN_CURSOR=$(jq -er '.as_of' <<< "$TURN_SNAPSHOT")
```

```bash
curl -sS --fail-with-body -N -G "$TURN_URL/events/stream" \
  -H "X-API-Key: $AGENTSCOPE_API_KEY" \
  -H "Accept: text/event-stream" \
  --data-urlencode "after=$TURN_CURSOR"
```

To reconnect a Session stream, replace `LAST_APPLIED_CURSOR` with the last successfully applied Session event cursor. This uses the header without an `after` query parameter:

```bash
LAST_APPLIED_CURSOR="LAST_APPLIED_SESSION_CURSOR"
curl -sS --fail-with-body -N "$SESSION_URL/events/stream" \
  -H "X-API-Key: $AGENTSCOPE_API_KEY" \
  -H "Accept: text/event-stream" \
  -H "Last-Event-ID: $LAST_APPLIED_CURSOR"
```

</Accordion>

## Interpret events and status

An SSE frame's `id` is a replay cursor, `event` is its type, and `data` contains JSON. Public events include `schema_version`, `id`, `type`, `session_id`, `created_at`, `cursor`, and `data`. Task-associated events also carry `turn_id`. Deduplicate by event ID and update existing resources by ID instead of adding another card for each notification.

`item.delta` appends message content, while `item.completed` supplies accumulated content and should replace it. Otherwise a client can display the same text twice. Tool events describe individual tool calls, `required_action.*` describes interactions, and `step.*` describes collaboration or workflow steps. Only the target Turn's explicit state establishes task completion; a tool or child finishing is not sufficient.

Managed Session snapshots expose runtime resource arrays, including inputs, subagents, and execution attempts. Generic Turn, Team, and Workflow snapshots use collections indexed by ID. Reuse `agentSessions.ts` and `agentSessionView.ts` for Managed views, or `serviceSessions.ts` for generic Turn views. These snapshot shapes are not identical structures.

## Pagination, reconnects, and expiration

Use `GET /events?after=...&limit=100` when a persistent connection is unnecessary. `next_cursor` continues pagination and `has_more` indicates immediately available events. An empty page does not establish completion; read Turn status or wait for further events.

```bash
EVENT_PAGE=$(
  curl -sS --fail-with-body -G "$SESSION_URL/events" \
    -H "X-API-Key: $AGENTSCOPE_API_KEY" \
    --data-urlencode "after=$CURSOR" \
    --data-urlencode "limit=100"
)
jq . <<< "$EVENT_PAGE"
```

Fetch the next page immediately only when `has_more` is true. Advance the cursor after processing the current page.

```bash
if jq -e '.has_more == true' <<< "$EVENT_PAGE" > /dev/null; then
  NEXT_CURSOR=$(jq -er '.next_cursor' <<< "$EVENT_PAGE")
  curl -sS --fail-with-body -G "$SESSION_URL/events" \
    -H "X-API-Key: $AGENTSCOPE_API_KEY" \
    --data-urlencode "after=$NEXT_CURSOR" \
    --data-urlencode "limit=100"
fi
```

A `410` response with `cursor_expired` means the resource's incremental history is no longer retained. Load a fresh snapshot and resume from its `as_of`. Do not resubmit a task just to restore observation. Cursors from other resources are rejected.

<span id="webhooks"></span>

## Notify a backend while users are offline

Register under a Session's `/webhooks` for events across its tasks, or under a Turn's `/webhooks` for just that task. Application credentials need `webhooks:write`. Replace the example URL with an HTTPS receiver you have deployed:

```bash
WEBHOOKS_URL="$SESSION_URL/webhooks"
WEBHOOK_JSON=$(
  curl -sS --fail-with-body "$WEBHOOKS_URL" \
    -H "X-API-Key: $AGENTSCOPE_API_KEY" \
    -H "Content-Type: application/json" \
    -H "Idempotency-Key: notifications-001" \
    --data-binary @- <<'JSON'
{
  "url": "https://your-app.example/agent-events",
  "event_types": [
    "turn.completed",
    "turn.failed",
    "required_action.created"
  ]
}
JSON
)
WEBHOOK_ID=$(jq -er '.webhook.id' <<< "$WEBHOOK_JSON")
SIGNING_SECRET=$(jq -er '.signing_secret' <<< "$WEBHOOK_JSON")
```

Store the returned `signing_secret` on the backend. Compute HMAC-SHA256 over `timestamp + '.' + raw request body` and compare it with `X-AgentScope-Signature: t=<seconds>,v1=<hex>`. Also check timestamp freshness and deduplicate event IDs. Verify the original bytes before parsing or reserializing JSON.

For one Turn, set `WEBHOOKS_URL="$SESSION_URL/turns/$TURN_ID/webhooks"` before registering. The management requests below reuse that registration URL and returned `WEBHOOK_ID`.

Delivery is at least once, so receivers may see duplicates. Persist the notification before acknowledging success, then read the latest Session or Turn state as needed. Inspect delivery with `GET /webhooks`, retry through `POST /webhooks/{webhookId}/retry`, or revoke with `DELETE`. Notification delivery does not make business operations idempotent or establish that an external transaction committed.

<Accordion title="Inspect, retry, and disable a Webhook">

Inspect subscriptions and delivery status:

```bash
curl -sS --fail-with-body "$WEBHOOKS_URL" \
  -H "X-API-Key: $AGENTSCOPE_API_KEY"
```

After recovering the receiver, retry failed delivery when needed:

```bash
curl -sS --fail-with-body -X POST "$WEBHOOKS_URL/$WEBHOOK_ID/retry" \
  -H "X-API-Key: $AGENTSCOPE_API_KEY"
```

Disable a subscription when notifications are no longer needed:

```bash
curl -sS --fail-with-body -X DELETE "$WEBHOOKS_URL/$WEBHOOK_ID" \
  -H "X-API-Key: $AGENTSCOPE_API_KEY"
```

</Accordion>

Automation inbound Webhooks trigger work; these outbound Webhooks notify receivers about its progress. See [Automation](/v2/en/service/automation) and [Operations](/v2/en/service/operations).
