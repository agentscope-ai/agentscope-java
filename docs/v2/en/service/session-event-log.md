---
title: "Sessions, tasks, and budgets"
description: "Steer a Managed Turn, answer an interaction, cancel work, and inspect durable results and budgets."
zh_link: /v2/zh/service/session-event-log
---

This page covers the source MQ / Redis Managed runtime and its `/v1/sessions` API. A Session pins an Agent definition and resource bindings; each Turn is one submitted request. Portal admits work, Brain reasons and commits native execution state, and Hands runs remote tools. Closing a page does not cancel execution. Other execution backends retain their own contracts; see [the service API](/v2/en/service/service-api).

Create a Session through the [Managed Session, Turn and Input API](/v2/en/service/claude-session-api), then set `SESSION_URL="$BASE_URL/v1/sessions/$SESSION_ID"` and retain the returned `TURN_ID`. Examples below use local mode. Use the credentials described in [production deployment](/v2/en/service/kubernetes#production-api-access) in production.

The current runtime uses a fresh Managed schema baseline. Previous Hands-through-Portal HTTP forwarding, result callbacks and standalone workspace-task protocols are removed; no old-state conversion or consumer-group offset migration is provided.

## How Sessions and Turns relate

Creating a Session persists its frozen configuration; it does not wait for bootstrap or a ready state. Editing an Agent does not update an existing Session. The Session update operation changes title and metadata, not its frozen definition or Environment binding.

Turns execute in MQ reception order within the Session. API admission order is a query coordinate, not execution priority. A long-running or suspended Turn retains its original ordered delivery until its durable terminal result lets the SDK acknowledge it. Subsequent Turns remain queued. Different Sessions can progress independently. A suspended Turn releases its active execution and MQ callback; its original message stays at the ordered head. An independent Resume continues the same Turn after its tool result or reply is durably available.

Retry an uncertain submission with the same `Idempotency-Key` and body. Reading an existing Turn or reconnecting SSE restores observation without creating another request.

## Read results and restore a page

Read the current Turn and a pinned snapshot:

```bash
curl -sS --fail-with-body "$SESSION_URL/turns/$TURN_ID"
SNAPSHOT=$(curl -sS --fail-with-body "$SESSION_URL/snapshot?limit=100")
CURSOR=$(jq -er '.as_of' <<< "$SNAPSHOT")
SNAPSHOT_ID=$(jq -er '.snapshot_id' <<< "$SNAPSHOT")
```

Every collection in `resources` shares `snapshot_id`, `as_of` and `expires_at`. Follow a collection's `next_cursor` through `/resources/{kind}` with the same `snapshot_id`; limits are 1–256. Snapshots last ten minutes, with at most 32 active snapshots per Session. On `snapshot_expired`, load a new snapshot.

```bash
curl -sS --fail-with-body -N -G "$SESSION_URL/events/stream" \
  -H "Accept: text/event-stream" --data-urlencode "after=$CURSOR"
```

Apply events only after restoring the snapshot. Persist a cursor after applying its event; reconnect through either Portal replica using that cursor or `Last-Event-ID`. Events and resource pages have different cursor contracts. See [SSE and event replay](/v2/en/service/sse-events) for accumulation, tool progress and bounded previews.

`waiting_tool` means Hands is still executing a tool and the platform will resume the same Turn from its checkpoint. It is not a request for the user to invent or supply a tool result. `waiting` with an actual required action needs a reply. Only `completed`, `failed` or `cancelled` establishes a terminal Turn outcome.

## Add requirements or context

A new question starts a new Turn. To steer or add context to an open Turn, submit an Input. Read the Turn's `/capabilities` and `inputs_open` before displaying controls.

```bash
INPUT_JSON=$(
  curl -sS --fail-with-body "$SESSION_URL/turns/$TURN_ID/inputs" \
    -H "Content-Type: application/json" -H "Idempotency-Key: correction-001" \
    --data-binary '{"type":"steer","payload":{"message":"Check the budget before continuing."}}'
)
INPUT_ID=$(jq -er '.id' <<< "$INPUT_JSON")
curl -sS --fail-with-body "$SESSION_URL/turns/$TURN_ID/inputs/$INPUT_ID"
```

Use `type=inject` at the same endpoint to add context to that Turn. Neither Input starts another Turn. Steering applies at a safe reasoning boundary and cannot change a model request already sent. The payload accepts either `message` or structured user input; [file references](/v2/en/service/files) follow the Session's immutable file contract.

Input `status=accepted` means durable admission. `delivery_status=delivered` means delivery, while `status=applied` confirms the input was committed with runtime state. A rejected Input retains `rejection_reason`. Retry the same intent with its original key; changing the content requires a new key.

## Answer a required action

Read `required_actions` from the snapshot and retain the actual `request_id`. Use it as `action_id` in a reply Input. A confirmation expects `allow` and an optional reason:

```bash
ACTION_ID="REQUEST_ID_FROM_REQUIRED_ACTION"
curl -sS --fail-with-body "$SESSION_URL/turns/$TURN_ID/inputs" \
  -H "Content-Type: application/json" -H "Idempotency-Key: answer-001" \
  --data-binary @- <<JSON
{"type":"reply","action_id":"$ACTION_ID","payload":{"allow":true,"reason":"Confirmed by the user"}}
JSON
```

For an action requesting external execution, send actual content and error status instead:

```json
{"type":"reply","action_id":"ACTUAL_REQUEST_ID","payload":{"content":[{"type":"text","text":"Actual execution result"}],"is_error":false}}
```

Set `is_error` to `true` when the external tool failed. The result retains its error state when reasoning resumes and the public tool status is `failed`. The Agent can use that result to continue reasoning; a failed tool does not by itself make the Turn fail.

These answer types are not interchangeable. Read the returned Input until applied or rejected, then continue observing the same Turn. An accepted answer does not establish task completion. Input waits persist the pending actions and checkpoint, then release active Harness ownership. Admission and suspension commit both check for an accepted reply/cancel, so either arrival order can schedule the same Turn for recovery. Runtime and transaction tests cover external replies and cancellation; full multi-replica acceptance remains recorded separately.

## Cancel and resume

Cancellation is another durable Input:

```bash
curl -sS --fail-with-body "$SESSION_URL/turns/$TURN_ID/inputs" \
  -H "Content-Type: application/json" -H "Idempotency-Key: cancel-001" \
  --data-binary '{"type":"cancel","payload":{}}'
```

You can also cancel an accepted Turn while required MCP discovery is pending, without waiting for the server to become available. After acceptance, wait for the Turn's actual terminal state. A committed completion can win the cancellation race. An uncertain external effect cannot be treated as stopped or safely repeated merely because a lease expired.

Long-tool suspension resumes automatically after Hands persists the covered original result and reliably publishes Resume. Brain first commits the tool event Inbox and checks the current suspension and every pending result, then restores the checkpoint. A Resume received before its result or suspension remains pending; duplicate delivery cannot create another physical invocation. Current native capabilities declare `resume=false`; the old explicit failed-Turn `/resume` and command-receipt protocol is not the native Managed contract. Do not start a replacement execution to resolve an unknown tool outcome.

<span id="budgets"></span>

## Usage, subagents, and budgets

Read `$SESSION_URL/usage` for the shared Session usage and `$SESSION_URL/turns/$TURN_ID/usage` for a Turn. A snapshot's usage and resources refer to its pinned cursor. Child execution has its own identity; use `resources.subagents` and supported child reads rather than treating a child completion as the parent result. Subtree summaries reflect reported child usage, not an atomic snapshot of every process.

Read the current budget before replacing its limits:

```bash
BUDGET=$(curl -sS --fail-with-body "$SESSION_URL/budget")
REVISION=$(jq -er '.revision' <<< "$BUDGET")
curl -sS --fail-with-body -X PUT "$SESSION_URL/budget" \
  -H "Content-Type: application/json" -H "If-Match: $REVISION" \
  --data-binary '{"max_model_calls":30,"max_total_tokens":100000}'
```

Update the root Session's shared budget. A stale revision returns `409`; reread before deciding whether to replace it. PUT `{}` clears the limits. Limits are checked before subsequent model calls using reported usage. In-flight parallel calls can overshoot, and cost checks require configured pricing. Raising a budget does not enable an unsupported explicit resume operation.

## Continue from a checkpoint

`GET $SESSION_URL/checkpoints?limit=20` lists committed checkpoint handles. It returns metadata, not native Agent state. Checkpoint cursors are independent of public event cursors; keep the list watermark while paging.

When `checkpoint_restore` or `fork` is enabled, submit a restore or Fork with a stable key and poll its operation through the source Session until `completed`. A Fork target must be registered and empty. The operation validates settled execution and the selected directory image, then appends a new Agent-state baseline. Saved historical images are reused; a historical checkpoint without an image cannot use a directory changed by later tool work. Subsequent Turns use the restored context and Workspace, while earlier public events remain visible. File assets, Artifact publications and external business effects are separate resources; restoring context does not rewind them. See [the operation contract](/v2/en/service/claude-session-api#restore-and-fork).

Archive, restore-from-archive and delete are separate Session lifecycle commands. They require an idempotency key and return their own receipt, read through `/lifecycle/{command}`. Follow that receipt until completion; lifecycle cancellation of accepted work does not prove immediate physical stop. Deletion is not a promise of immediate removal of every audit record.

## Background notifications and client reuse

Use `GET /events?after=...&limit=100` for finite replay or SSE for continuous observation. The native Java and TypeScript SDK entry is `ServiceClient.managedSessions()`; Python uses `managed_sessions()`. Preserve stable write keys and the Session's opaque cursors. Legacy backend `/actions`, `/commands`, export or trace examples are not interchangeable with these native routes.

The Console's Managed view uses `agentSessions.ts` and `agentSessionView.ts`. A task adapter can project these events into a separate public execution view with its own cursor. Never reuse that cursor on the original Session. Notification and export support must follow the capabilities of the selected backend; public observations must not include private recovery state or credentials.

## Managed runtime protocol

Recovery scans committed event metadata and loads only the last checkpoint's state body. Turn and pending-interaction queries do not load historical checkpoints. Dispatch collection and Recorder startup checks reuse their respective projection scans; historical message IDs from imported baselines remain available. Log wrappers within one execution share a bounded read cache, discarded on takeover. Tool tasks for one native call are fetched together. These read optimizations preserve the kernel's writer-acquisition, tool-result reconciliation transaction and subsequent Recorder restoration boundaries. Full native history reads still return complete payloads.

An unconfirmed native commit stops the active Harness through the existing Guard failure path. MQ redelivery acquires a new epoch and restores the committed head, native history and checkpoint; it does not replay a historical batch receipt. Tool results remain durable business facts: `ma.tool_task.result_hash` records the original result identity atomically with its settled result. Matching redelivery is harmless even after result Outbox cleanup; conflicting content or a different execution is rejected. Managed SessionLog does not support permanent `sealWriter`; Redis ownership, epoch fencing and local handoff protection remain in effect.

One model tool can contain multiple Hands operations. `tool.operation.completed` only updates an operation; the actual model tool result completes the tool. Output and progress continue while Brain is suspended, and a replacement Hands collector resumes the original execution's durable output cursor. The [tool event contract](/v2/en/service/sse-events#managed-tool-output-and-operation-progress) explains gaps, final watermarks and preview limits.

Tools with unknown effects are not automatically rerun. The retired Workspace journal, automatic sandbox-recreation, `authorization_generation`, `tool-recovery` and `workspace-recovery` protocols are not available on `/v1/sessions`. Deployment acceptance remains separate from source-level support.

## Privileged native trace

The native Session API exposes committed public events and checkpoint metadata. It does not currently provide the old privileged `/trace` endpoint. Internal recovery records, tool-source bytes and checkpoint objects are separate from public SSE and snapshots. Operators must follow the deployment's internal observability procedure rather than using an obsolete HTTP recovery or trace example.
