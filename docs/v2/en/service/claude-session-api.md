---
title: "Managed Session Event API"
description: "Draft 3 first release: event admission, Turn inputs, history, live events and independent output."
zh_link: /v2/zh/service/claude-session-api
---

This page describes the AgentScope Service Draft 3 source protocol. Deployment is a separate upgrade;
earlier cluster acceptance records describe the previous deployed version. Compatibility is a
candidate; pinned SDKs and actual verification are recorded under `agentscope-service/tests/session-protocol/`.

A Session pins Agent/Environment configuration. HTTP success means durable admission; Input applied
means runtime settlement; Turn terminal state describes execution. MQ, checkpoints and automatic
Resume continue to provide execution reliability.

## Create and retrieve

POST /v1/sessions accepts agent (ID or version reference), environment_id, title and string metadata.
Use existing resource-management APIs to prepare Agents and Environments. GET /v1/sessions/{session}
returns the configuration snapshot and actual status. Metadata and events cannot change caller scope.

Use x-api-key for a platform application key or a Bearer platform token, never both. Native scope
headers are X-AgentScope-Tenant/Namespace. Claude clients use anthropic-version 2023-06-01 and
anthropic-beta managed-agents-2026-04-01. Anthropic Workspace-to-platform scope mapping is unavailable.

## Unified interaction

All public interactions use POST /v1/sessions/{session}/events:

```json
{"events":[{"type":"user.message","content":[{"type":"text","text":"Analyze performance"}],"agentscope_service":{"intent":"new_turn"}}]}
```

new_turn creates an independent Turn and forbids turn_id. steer/inject use user.message and require
an existing turn_id. cancel uses user.interrupt with turn_id. Inputs apply at safe reasoning boundaries;
while a tool runs, steer/inject wait for its real result and resume rather than cancelling it.

Explicit intents require Idempotency-Key. HTTP 200 returns data in request order, stable Event and
Turn/Input identities and frozen admission receipts. Retry the same body with the retained original
key after an uncertain response. Query current state separately. An extended batch admits atomically:
1–50 events and at most 1 MiB JSON. Physical work starts after commit. New Turns within a batch publish
in array order; concurrent HTTP arrival does not define execution order.

Ordinary Claude calls support one idle user.message, user.interrupt, or interrupt followed by one
ordinary message. Plain append during active execution, multiple ordinary messages, client tool replies
and thread interactions remain unavailable.

## Read execution and results

Under /v1/sessions/{session}:

| Path | Purpose |
| --- | --- |
| /turns and /turns/{turn} | Current Turn state and identity |
| /turns/{turn}/inputs and /turns/{turn}/inputs/{input} | Input admission and settlement |
| /turns/{turn}/output | Independent final output; after/limit pagination |
| /events | History; page/limit/order/types[] and time filters |
| /events/stream | Events processed after connection |
| /capabilities | Revision, limits and enabled features |

Turn states are accepted/preparing/running/waiting_tool/waiting/completed/failed/cancelled. Input states
are accepted/applied/rejected. Resume preserves the Turn. Accepted cancellation does not mean stopped.
Session states are idle/running/rescheduling/terminated; queueing is not rescheduling.

Output returns output_available, turn_status, data, page output_event_ids and next_cursor/has_more.
Before final output, return 200 and an empty collection. Terminal without output differs from a final
projection temporarily unavailable, which is retryable 503. SDK final_output/finalOutput waits through
Turn reads, then pages output. It does not need SSE or snapshots.

## History and live events

Standard events have stable id/type/processed_at and type-specific fields. Pending admitted input
is visible with processed_at=null. created_at[gt/gte/lt/lte] compares processed_at. History returns
data/next_page and freezes its upper bound and projection versions. Preserve scope and query on pages.

AgentScope-Extensions: turn-inputs-v1 adds agentscope_service fields and agentscope_service.turn/input
notifications. Standard-only streams omit those unknown types. The official SDK beta=true transport
query flag is accepted; it does not change business semantics.

SSE starts after connection and does not replay history. after/Last-Event-ID/preview are unsupported.
Reconnect by opening/buffering a new stream, reading history, then deduplicating Event IDs; the console
uses this order. Keepalive is not completion. Observer disconnection does not cancel execution. A Turn
ending does not close the Session stream; permission revocation stops it.

## First-release capabilities

Listing/updating/archiving/deleting Sessions, files, client tool confirmations/results, workflows,
threads, reliable delivery, snapshots and deltas are disabled. Requests return unsupported_capability;
the console hides their controls. Create/open a Session by ID, inspect history/output, and submit
new_turn or targeted steer/inject/cancel.

Java and TypeScript use ServiceClient.managedSessions(); Python uses managed_sessions(). Every
platform interaction helper posts events. A generated key is fixed for one invocation; retain and
explicitly reuse it when retrying across invocations.

### Restore and Fork

Public checkpoint restore and fork are not enabled by the Draft 3 first release. Check `/capabilities` before offering those controls. Runtime recovery still restores committed checkpoints internally; this does not authorize a client to replace a Session's execution state or create a fork. Earlier checkpoint mutation examples do not apply to this protocol revision.

{/* Preserve earlier anchors; current capability limits supersede the old examples. */}
<span id="create-a-session-and-submit-a-turn"></span>
<span id="archive-delete-and-restore"></span>
<span id="inputs-within-a-turn"></span>
<span id="queries-pages-and-subscriptions"></span>
<span id="snapshot-consistency"></span>
<span id="checkpoint-metadata"></span>
<span id="files-and-deliverables"></span>
