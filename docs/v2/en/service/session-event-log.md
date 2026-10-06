---
title: "Managed native API: sessions and tasks"
description: Create Managed Agent sessions, submit background tasks, read results, answer interactions, cancel and resume over HTTP.
zh_link: /v2/zh/service/session-event-log
---

The Managed native session API is one part of the platform’s Agent APIs, providing direct control of the hosted runtime. The service runs Agents, stores conversations and schedules tasks; your application supplies input, displays results and handles user interaction. Execution continues when a page or SSE connection closes.

Use `/api/v1/agent-sessions` on the Gateway. First [create a Managed Agent](/v2/en/service/create-managed-agent), then follow the workflow below. To run an Agent inside your own Java process, use the separate [AgentSession guide](/v2/en/docs/harness/session-log).


To publish an Agent or Team to applications, start with the [unified Service API](/v2/en/service/service-api). This page covers native capabilities for direct Managed session control.

## Choose capabilities by scenario

| Scenario | API | What to observe |
| --- | --- | --- |
| Multi-turn chat or a background assistant | Create session; POST turns | Messages, tools and the target turn state |
| Refresh and restore content still being generated | GET snapshot; GET events/stream?after=as_of | Historical messages, tool cards and subsequent deltas |
| Correct direction or add context during execution | POST turns/{turn}/steer or inputs/inject | input.accepted → applied / rejected |
| Tool approval or externally executed results | POST turns/{turn}/actions | Required action receipt, resolution or delivery failure |
| Stop work or continue an interrupted task | POST turns/{turn}/cancel or resume | Explicit turn state; resume keeps the turn |
| File input and delivery | files, artifacts | File references, publication and download |
| Follow delegation and control usage | subagents, usage, budget | Child sessions, usage and budget events |
| Try another path from earlier context or export an audit | checkpoints, fork, export | A restore fact, new session or public JSONL |
| Notify a backend while the user is offline | webhooks | Signed notification, followed by an event read |

Start with the [chat example](/v2/en/service/agent-api-chat). This guide explains operations and their constraints; the [SSE guide](/v2/en/service/sse-events) covers event fields and rendering rules.

## Run your first task

Create a session once, submit a turn, load snapshot, and subscribe to SSE after its as_of cursor. Answer required actions as they arrive and use the target turn's explicit outcome to determine completion.

## Execution identity and logical turn state

sessionId identifies the ongoing conversation; turnId identifies a logical task; run_id identifies an execution attempt. POST turns starts a new task. Resume and action answers continue the same turn, potentially with a new run. Refreshing the page or reconnecting SSE creates neither.

For example, S1 runs T1 as R1, then suspends for approval. An answer continues T1 as R2. Refreshing reads S1's snapshot and subsequent events. A new question after completion starts T2. Steer adjusts the current task; inject adds context without starting inference.

## Create a session and submit work

Use an existing Managed Agent, Environment and its owner's platform user Bearer token. Endpoint X-API-Key credentials do not replace this token. Login is described in [API authentication](/v2/en/service/api-reference). These commands use curl and jq; BASE_URL is the Gateway origin without a trailing slash.

```bash
export BASE_URL='http://localhost:18080'
export TOKEN='YOUR_USER_TOKEN'
export AGENT_ID='YOUR_MANAGED_AGENT_ID'
export ENVIRONMENT_ID='YOUR_ENVIRONMENT_ID'
SESSION_JSON=$(curl --fail-with-body -sS "$BASE_URL/api/v1/agent-sessions" \
  -H "Authorization: Bearer $TOKEN" -H 'Content-Type: application/json' \
  -d "$(jq -n --arg agent "$AGENT_ID" --arg env "$ENVIRONMENT_ID" \
        '{agent:$agent, environmentId:$env}')")
SESSION_ID=$(printf '%s' "$SESSION_JSON" | jq -er '.id')
SESSION_URL="$BASE_URL/api/v1/agent-sessions/$SESSION_ID"

TURN_KEY='research-request-20260930-001'
TURN_JSON=$(curl --fail-with-body -sS "$SESSION_URL/turns" \
  -H "Authorization: Bearer $TOKEN" -H 'Content-Type: application/json' \
  -H "Idempotency-Key: $TURN_KEY" \
  -d '{"message":"Review the material and identify open questions."}')
TURN_ID=$(printf '%s' "$TURN_JSON" | jq -er '.id')
```

Creation accepts camelCase fields such as `agent` and `environmentId`. The environment may be omitted when the agent has a default. Creation does not submit a message; retain the session ID across page refreshes.

Turn submission returns 202 with `{id,sessionId,status,createdAt,errorCode}`, initially queued. Admission is durable but does not mean input reached the model. Preserve a nonempty Idempotency-Key of at most 256 characters and the same message on network retries. Same identity/key/input returns the same turn; changing input under the key returns 409.

Turns execute in admission order within a session. An earlier unresolved turn blocks subsequent queued work; queued input is not injected into the currently running request.

## Read replies and progress

```bash
SNAPSHOT=$(curl --fail-with-body -sS "$SESSION_URL/snapshot" -H "Authorization: Bearer $TOKEN")
CURSOR=$(printf '%s' "$SNAPSHOT" | jq -er '.as_of')
curl --fail-with-body -N -G "$SESSION_URL/events/stream" \
  -H "Authorization: Bearer $TOKEN" --data-urlencode "after=$CURSOR"
```

Snapshot includes items, tools, turns, runs, required_actions, action_commands, inputs, artifacts, subagents and usage at one committed prefix, as_of. The embedded session is current control-plane metadata. Items include committed partial messages; tools contain arguments, progress, results and status. Restore that prefix before applying new events.

Update messages by item_id and tools by `(turn_id, tool_call_id)`. item.completed replaces accumulated content for that item. A task can produce many messages and tool calls. Only turn.completed for the target turn indicates task success; a completed run, message, tool or child Agent is insufficient.

Use GET `/events?after=…&limit=100` for event history. Use GET `/resources/items?limit=100` for a long resource list, then pass its next_cursor. Resource pages retain their original snapshot/as_of for 15 minutes; 410 means restart pagination. **Resource page cursors are not SSE cursors**; stream from as_of or an event cursor. See [SSE integration](/v2/en/service/sse-events).

## Steer running tasks and submit structured input

| Intent | Operation | Effect |
| --- | --- | --- |
| Start independent work | POST `/turns` | Queue a new logical turn |
| Correct a running task | POST `/turns/{turn}/steer` | Apply at the next execution step; 409 if the task no longer accepts steering |
| Add context without starting work | POST `/inputs/inject` | Consume at the next execution step; retain while idle |

All three accept a stable Idempotency-Key and either message or input. Steer/inject return input_id; input.accepted means durable receipt, while input.applied means runtime consumption. Steering cannot rewrite a model request already sent.

```bash
curl --fail-with-body -sS "$SESSION_URL/turns/$TURN_ID/steer" \
  -H "Authorization: Bearer $TOKEN" -H 'Content-Type: application/json' \
  -H 'Idempotency-Key: correction-001' -d '{"message":"Check the budget first; do not send email yet."}'
```

```json
{"input":[{"role":"user","content":[{"type":"text","text":"Analyze the attachment"},{"type":"file","file_id":"file_..."}]}]}
```

Choose message or input, never both. Structured input allows 1..100 user messages with text, image, audio, video, data or uploaded file references. Media source uses Core ContentBlock format, for example `{"type":"url","url":"https://example.com/image.png"}` or `{"type":"base64","media_type":"image/png","data":"..."}`. Supported media and context size depend on the selected model. Submit confirmations and external tool results through actions, not forged system/assistant/tool messages.

## Actions, cancellation and recovery

### Answer required actions

Use request_id, turn_id, kind and tool_call from required_action.created or snapshot.required_actions. For confirmation:

```bash
curl --fail-with-body -sS "$SESSION_URL/turns/$TURN_ID/actions" \
  -H "Authorization: Bearer $TOKEN" -H 'Content-Type: application/json' \
  -H 'Idempotency-Key: approval-001' \
  -d '{"answers":[{"request_id":"YOUR_REQUEST_ID","allow":true,"reason":"Approved by the user"}]}'
```

For external_execution, supply output and optional is_error instead of allow. Each submission accepts 1..100 actual pending requests. accepted means receipt; resolved means runtime completion. rejected is a delivery failure, not a user denial. Requests still pending reappear with kind/tool_call; resolved requests do not. GET `/turns/{turn}/actions` reports accepted/resolved/rejected command states. Retry an identical submission with the same key; inspect current pending requests before correcting rejected answers with a new key.

### Cancel a task

```bash
curl --fail-with-body -sS -X POST "$SESSION_URL/turns/$TURN_ID/cancel" -H "Authorization: Bearer $TOKEN"
```

A cancellation request is distinct from execution stopping. Wait for the explicit turn result. Already-dispatched external effects may remain. Cancelling an idle interrupted/suspended turn closes its pending interactions and unapplied steering; unknown tool outcomes must first be reconciled.

### Resume interrupted execution

```bash
curl --fail-with-body -sS -X POST "$SESSION_URL/turns/$TURN_ID/resume" \
  -H "Authorization: Bearer $TOKEN" -H 'Idempotency-Key: resume-001'
```

Resume keeps the logical turn and starts another attempt from committed state. It accepts failed/interrupted turns and requires_action without unanswered interactions. Answer actions or reconcile unknown tools first. It does not restore threads or undo effects. Send a stable `Idempotency-Key`: retrying it after a lost response returns the current state without resuming twice. A later intentional resume uses a new key; queued/running turns cannot accept a new resume.

### Administrator checkpoint and tool reconciliation

Enable `builder.agent-api.trace-enabled=true`; session ownership and ROLE_ADMIN or ROLE_SESSION_TRACE are still required.

| Relative route | Purpose |
| --- | --- |
| `GET /trace?after=0&limit=100` | Native numeric seq paging; limit 1..500; data/as_of_seq/next_seq |
| `GET /trace/recovery` | asOfSeq/stateJson/uncertainToolCalls/activeRuns inspection |
| `POST /trace/reconcile` | `{reason,outcomes}` mapping exact unresolved toolCallIds to verified ToolResultBlock values |
| `GET /trace/subagents/{child}` | Directly linked child's native history under parent authorization |

This administrative example assumes call-42 is the only unresolved call and its outcome has been independently verified. Use a trace-authorized owner token and actual IDs:

```bash
curl --fail-with-body -sS "$SESSION_URL/trace/recovery" \
  -H "Authorization: Bearer $TOKEN"
curl --fail-with-body -sS "$SESSION_URL/trace/reconcile" \
  -H "Authorization: Bearer $TOKEN" -H 'Content-Type: application/json' \
  -d '{"reason":"Verified order ORD-42 by business idempotency key","outcomes":{"call-42":{"type":"tool_result","id":"call-42","name":"create_order","state":"SUCCESS","output":[{"type":"text","text":"Order ORD-42 exists"}]}}}'
```

Reconciliation must cover exactly the unknown tool IDs with verified, non-suspended results. Checkpoint APIs below do not bypass this requirement.

## Files and artifacts

Upload an immutable file, then use file_id in structured input or artifact publication. Same-key retries must preserve content and metadata.

```bash
FILE_JSON=$(curl --fail-with-body -sS "$SESSION_URL/files" \
  -H "Authorization: Bearer $TOKEN" -H 'Idempotency-Key: report-file-001' \
  -H 'X-File-Name: report.pdf' -H 'Content-Type: application/pdf' --data-binary @report.pdf)
FILE_ID=$(printf '%s' "$FILE_JSON" | jq -er '.file_id')
curl --fail-with-body -sS "$SESSION_URL/artifacts" \
  -H "Authorization: Bearer $TOKEN" -H 'Content-Type: application/json' \
  -H 'Idempotency-Key: publish-report-001' -d "$(jq -n --arg id "$FILE_ID" '{file_id:$id}')"
curl --fail-with-body -sS "$SESSION_URL/files/$FILE_ID/content" \
  -H "Authorization: Bearer $TOKEN" -o downloaded-report.pdf
```

Percent-encode UTF-8 filenames in X-File-Name. Default upload limit is 16 MiB; model input, accumulated context and journal commits have separate size limits. Downloads require session ownership. You may instead publish an external HTTPS reference `{name,uri,media_type?,sha256?}`. DELETE `/artifacts/{id}` revokes publication, retaining file content and history.

## Child Agents, usage and budgets

GET `/subagents` lists child associations. Use childSessionId to read `/subagents/{child}/snapshot`, `/events` or `/events/stream`. Access requires ownership of the parent and a verified descendant link. Each child has separate items, turns, runs and cursors. Use the child's history for asynchronous completion; never mix parent and child cursors.

GET `/usage` reports this session's model usage. Add `?include_children=true` for linked descendants and per-session session_watermarks; this is not one atomic tree-wide snapshot. Usage deduplicates model calls. Configured prices add estimated_cost, currency, cost_complete and unpriced_calls. Unknown prices do not become zero-cost claims.

```bash
curl --fail-with-body -sS -X PUT "$SESSION_URL/budget" \
  -H "Authorization: Bearer $TOKEN" -H 'Content-Type: application/json' \
  -d '{"max_model_calls":30,"max_total_tokens":100000}'
```

Budgets cover this session and calls by its derived child Agents. max_model_calls uses atomic admission reservations. max_total_tokens and max_cost check reported usage before the next call; they cannot hard-cap in-flight or concurrent usage. Cost limits require currency and pricing. Missing usage/prices prevent further admission under the corresponding limit. After budget.exceeded, adjust limits and explicitly resume the failed turn. GET `/budget` reads limits/accounting; PUT `{}` clears limits. Estimated cost is not a provider invoice.

## Fork or restore a checkpoint

GET `/checkpoints` returns opaque checkpoint IDs, times and reasons, without private state or prompts. Select an actual returned ID:

```bash
curl --fail-with-body -sS "$SESSION_URL/checkpoints" -H "Authorization: Bearer $TOKEN"
curl --fail-with-body -sS "$SESSION_URL/checkpoints/restore" \
  -H "Authorization: Bearer $TOKEN" -H 'Content-Type: application/json' \
  -H 'Idempotency-Key: restore-001' \
  -d '{"checkpoint_id":"YOUR_CHECKPOINT_ID","reason":"Restart from verified context"}'
```

Restore appends a state-restoration fact without truncating audit history. Submit a new turn afterward; old tasks are not requeued. To explore separately, first create an empty target session with the same Agent, then POST `/fork` with `{target_session_id,checkpoint_id,reason}` and an idempotency key. Configure the target's environment, credentials and working files separately. Fork copies Agent state, not environments, child sessions, hosted files or budgets.

Before either operation, settle or cancel outstanding turns, resolve interactions and unknown tools, and consume pending inputs. A selected checkpoint cannot have unresolved dispatched tools. Restore never undoes external effects. POST `/restore` only unarchives a session and differs from `/checkpoints/restore`.

GET `/export` downloads the current public event prefix as JSONL. It excludes private prompts, raw model reasoning, credentials and full checkpoint state.

## Receive notifications without an open page

Register a session webhook from your backend. The destination must use HTTPS on an operator-allowlisted host and port 443.

```bash
curl --fail-with-body -sS "$SESSION_URL/webhooks" \
  -H "Authorization: Bearer $TOKEN" -H 'Content-Type: application/json' \
  -H 'Idempotency-Key: task-notifications-001' \
  -d '{"url":"https://notify.example.com/agent-events","event_types":["turn.completed","turn.failed","turn.requires_action"]}'
```

Registration starts at the current event head. Save signing_secret; list responses omit it. Notifications contain id, type, session_id, cursor, created_at and event_url. Fetch the referenced event using authentication. Verify X-AgentScope-Signature by computing HMAC-SHA256 over `X-AgentScope-Timestamp + "." + rawBody`, using the UTF-8 signing_secret as key; the header is `v1=<hex>`. Compare in constant time, check timestamp freshness and deduplicate event IDs.

Delivery is at least once. Return 2xx after receipt. Failures back off exponentially and pause after eight attempts. GET `/webhooks/{id}/deliveries` inspects attempts; POST `/webhooks/{id}/retry` resumes; DELETE `/webhooks/{id}` disables delivery. Updates during active delivery return 409 and can be retried later. Duplicate notifications must not repeat business side effects.

## Where records live

| Record | Default storage | Purpose |
| --- | --- | --- |
| Working files | Environment workspace or configured Filesystem | Agent business files |
| Native journal and checkpoints | Shared BaseStore runtime/sessions namespace | Recovery and tool reconciliation |
| Public events and turn/action commands | Data Plane database | HTTP state, history, SSE and scheduling |
| Files, budgets, webhooks and projections | Shared BaseStore runtime/agent-api namespace | Resources shared across replicas; projections can be rebuilt |

Filesystem and BaseStore may use distributed backends. Replicas must share durable storage supporting conditional writes. Clients do not depend on internal paths. Operators configure upload size with builder.agent-api.files.max-bytes and webhook hosts with webhooks.allowed-hosts. pricing.models is JSON containing per-million input/output token prices; pricing.currency sets the currency. Supply a SessionUsagePricer Bean for custom pricing.

## Integrating existing applications

Console's Session **Execution** tab demonstrates message/tool restoration, file input, steer/inject and child views. Reuse frontend/src/api/agentSessions.ts and the agentSessionView.ts reducer. Reload snapshot, then apply committed suffix events even if the page closed during model generation or tool execution.

Existing Chat, `/api/sessions` and Endpoint surfaces keep their own protocols. Do not mix DTOs or cursors. Machine-readable contracts are agentscope-service/docs/agent-api/openapi-v1.json and public-event-v1.schema.json. See the [API reference](/v2/en/service/api-reference) for routes.

For upload limits, webhook hosts and model pricing, see [configuration](/v2/en/service/configuration#agent-api-settings).
