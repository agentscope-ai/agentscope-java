---
status: accepted
---

# Reply delivery is owned by the host's durable delivery queue

A Weixin reply is not sent by the Channel extension on the inbound path. The AgentScope host
persists the reply in its own delivery queue (`channel_deliveries`) as soon as the Agent produced
it, the accepted inbound message completes, and a host worker sends the reply through the channel's
provider transport. The extension owns the protocol call and returns the provider's receipt; it owns
no delivery queue of its own.

## Context

The extension previously sent the reply inline, inside the dispatch of the inbound message. A
provider rejection therefore failed the whole dispatch: the message returned to `PENDING`, the
Agent ran again, and the reply was sent again. One user message produced eight Agent executions and
eight replies in the development deployment. Bounding the inline retry only traded the failure for
a different one — the reply was then dropped, because nothing persisted it.

The host already had the machinery this needs: `channel_deliveries` with `pending/submitted/
provider_accepted/failed`, an attempt counter, exponential backoff, a lease per claim, a manual
re-send endpoint, and `event_key` uniqueness for idempotency. The `Channel` interface already
declared `deliverWithReceipt(address, message, deliveryId)`, documented as returning the provider's
message ID so a caller can keep a notification pending.

## Decision

- The scheduler enqueues the reply through an internally authenticated control-plane operation and
  returns no reply to the channel, so nothing is sent inline.
- The idempotency unit is the accepted inbound message: the event key is
  `channel-reply:<channelId>:<accountId>:<inboundMessageId>`.
- `WeixinChannel` implements `deliverWithReceipt(...)` and returns iLink's `message_id`. A failure
  propagates so the queue keeps the notification pending.
- `-14` (expired credential) keeps the notification pending without consuming an attempt, because
  only a human re-authorization can clear it: the receipt carries `deferSeconds`, which returns the
  attempt and defers the next one.
- A reply is exempt from the work-configuration gates that decide whether an issue, comment or
  approval notification may reach a peer. Its authority is the sender's binding, checked when the
  reply is queued, not the channel's notification settings.
- A courtesy reply ("bind your account first", "no permission") answers a peer that is not bound,
  which the delivery queue refuses to claim; it therefore stays on the channel's inline path, where
  no Agent ran and a failed send cannot replay one.
- The message completes once the reply is persisted, not once it is delivered. A delivery failure
  never replays the Agent.
- Standalone deployments keep the inline path, where a single attempt is made and a failure is
  reported through `WeixinRuntimeListener.onDeliveryFailed`; they have no durable queue to hand a
  reply to, and the extension README says so.

## Consequences

- A provider outage no longer re-runs Agents and no longer duplicates replies; it delays them.
- Reply delivery is visible and operable in the console, including manual re-send of a failed
  notification, without inventing a second queue inside the extension.
- The extension cannot be used standalone as a durable delivery system; a host that needs restart
  recovery must supply one.
- A crash between "Agent finished" and "reply persisted" still replays the Agent. Removing that
  window needs data-plane idempotency keyed by the inbound message, which the channel cannot
  provide on its own.
