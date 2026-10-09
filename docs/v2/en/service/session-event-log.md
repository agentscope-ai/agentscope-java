---
title: "Sessions, tasks, and budgets"
description: "Add requirements, answer pending actions, cancel or resume submitted work, and manage usage and budgets."
zh_link: /v2/zh/service/session-event-log
---

A Session records work between an application and an execution target. A Turn is one task submitted to that Session. Agents, Teams, and Workflows share the Session API; Managed Agents additionally support conversation context, steering, checkpoints, and subagents. Closing a page or disconnecting an event stream does not cancel background work.

Start with [Integrate applications with the Session API](/v2/en/service/service-api) to obtain `SESSION_URL`, `TURN_ID`, and `AGENTSCOPE_API_KEY`. Once a task is submitted, users may need to change requirements, answer an Agent's question, or stop and resume execution. This page explains how those operations apply to an existing Session and Turn. An authorized platform Bearer token can also access a Session. Application credentials need both the required scope and an explicit grant for the target resource.

## How Sessions and Turns relate

Session creation freezes the target and its configuration. Subsequent Turns use that configuration, so editing an Agent does not silently change an existing conversation. Create a new Session to use the updated definition. Workflow Sessions select a published revision, either explicitly or by resolving the latest published revision at creation.

<Accordion title="Update Managed runtime resources">

A Managed Session can update runtime resource bindings with `PATCH`. Complete an initial Turn to establish its runtime Session, then fill in an Environment ID you may use. This changes only the Environment and keeps the frozen Agent definition version.

```bash
ENVIRONMENT_ID="YOUR_ENVIRONMENT_ID"
curl -sS --fail-with-body -X PATCH "$SESSION_URL" \
  -H "Authorization: Bearer $TOKEN" \
  -H "X-AgentScope-Tenant: $TENANT" \
  -H "X-AgentScope-Namespace: $NAMESPACE" \
  -H "Content-Type: application/json" \
  --data-binary @- <<JSON
{
  "environmentId": "$ENVIRONMENT_ID"
}
JSON
```

</Accordion>

Turns in a Session execute in acceptance order. A task waiting for approval holds later Turns in the queue until it finishes. Managed Agents retain conversation context between Turns. Each Team or Workflow Turn starts independent work, while the Session collects its progress and results. A common interface does not imply identical memory or interaction capabilities; read `/capabilities` before displaying controls.

A `202 Accepted` response with status `queued` means the task has been accepted and has not finished. Retry a network request with the same `Idempotency-Key` and body to retrieve the original Turn. Use a new key only for a new task. When a page refreshes, read the existing Session snapshot and events instead of resubmitting input.

## Read results and restore a page

Read `GET /turns/{turnId}` for task status. To restore the full page, load the Session `/snapshot`, then connect to `/events/stream` with the returned `as_of`. A view limited to one Turn should pair that Turn's `/snapshot` with its own `/events/stream`. Session and Turn cursors are not interchangeable.

```bash
curl -sS --fail-with-body "$SESSION_URL/turns/$TURN_ID" \
  -H "X-API-Key: $AGENTSCOPE_API_KEY"
```

<Accordion title="List Sessions and previous Turns">

The Session list defaults to active records; `status=all` also includes archived records. Both lists return `items` and `next_offset`; pass a non-null next offset as `offset` in the next request.

```bash
curl -sS --fail-with-body -G "$BASE_URL/api/v1/agent-sessions" \
  -H "X-API-Key: $AGENTSCOPE_API_KEY" \
  --data-urlencode "status=all" \
  --data-urlencode "limit=20" \
  --data-urlencode "offset=0"
```

```bash
curl -sS --fail-with-body "$SESSION_URL" \
  -H "X-API-Key: $AGENTSCOPE_API_KEY"
```

```bash
curl -sS --fail-with-body -G "$SESSION_URL/turns" \
  -H "X-API-Key: $AGENTSCOPE_API_KEY" \
  --data-urlencode "limit=20" \
  --data-urlencode "offset=0"
```

</Accordion>

Managed Session snapshots include messages, tools, inputs, required actions, and subagents. Team, Workflow, and generic Turn snapshots contain collections indexed by ID and execution steps. Clients must handle the appropriate snapshot shape. Session and Turn identity, control paths, and resumable event delivery are shared. See [SSE and event replay](/v2/en/service/sse-events) for accumulation rules.

## Add requirements or context

Submit a new Turn for a new question. To change the subsequent steps of an active Managed task, use `steer` when supported. Steering cannot change a model request that has already been sent. To add background context without starting inference, use the Session `/inputs/inject` resource.

Read the Turn’s available commands before displaying controls:

```bash
curl -sS --fail-with-body "$SESSION_URL/turns/$TURN_ID/capabilities" \
  -H "X-API-Key: $AGENTSCOPE_API_KEY"
```

```bash
curl -sS --fail-with-body "$SESSION_URL/turns/$TURN_ID/steer" \
  -H "X-API-Key: $AGENTSCOPE_API_KEY" \
  -H "Content-Type: application/json" \
  -H "Idempotency-Key: correction-001" \
  --data-binary @- <<'JSON'
{
  "message": "Check the budget first; do not send email yet."
}
JSON
```

Managed input accepts either a nonempty `message` or an `input` array of user messages, but not both. Structured input carries text and file references; see [Files and artifacts](/v2/en/service/files). `input.accepted` means persisted, while `input.applied` means consumed by the runtime. Do not present acceptance as completed processing.

Add context without starting inference:

```bash
curl -sS --fail-with-body "$SESSION_URL/inputs/inject" \
  -H "X-API-Key: $AGENTSCOPE_API_KEY" \
  -H "Content-Type: application/json" \
  -H "Idempotency-Key: context-001" \
  --data-binary @- <<'JSON'
{
  "message": "Additional context: the installation guide is for developers new to Docker."
}
JSON
```

## Answer a required action

An Agent creates a required action when it needs confirmation or an external execution result. Save its `request_id`, explain the requested operation to the user, and submit their actual decision to the corresponding Turn. Replace the placeholder below with an ID returned by the service:

```bash
curl -sS --fail-with-body "$SESSION_URL/turns/$TURN_ID/actions" \
  -H "X-API-Key: $AGENTSCOPE_API_KEY"
```

Choose an actual pending `request_id` and check the required answer type. For a tool confirmation, use the following answer; set `allow` to `false` and explain the reason to deny it.

```bash
REQUEST_ID="REQUEST_ID_FROM_PENDING_ACTION"
COMMAND_JSON=$(
  curl -sS --fail-with-body "$SESSION_URL/turns/$TURN_ID/actions" \
    -H "Authorization: Bearer $TOKEN" \
    -H "X-AgentScope-Tenant: $TENANT" \
    -H "X-AgentScope-Namespace: $NAMESPACE" \
    -H "Content-Type: application/json" \
    -H "Idempotency-Key: approval-001" \
    --data-binary @- <<JSON
{
  "request_id": "$REQUEST_ID",
  "payload": {
    "allow": true,
    "reason": "Confirmed by the user"
  }
}
JSON
)
COMMAND_ID=$(jq -er '.command.id' <<< "$COMMAND_JSON")
```

The response includes a command receipt and status URL. Follow that receipt and subsequent task events; accepting an answer does not complete the whole task. Retry an unchanged answer with its original key. Before changing an answer, reread the action. The `interact` scope permits interaction requests but does not turn an application credential into a named human approver. Identity-bound approvals still require an authorized user.

```bash
curl -sS --fail-with-body "$SESSION_URL/turns/$TURN_ID/commands/$COMMAND_ID" \
  -H "Authorization: Bearer $TOKEN" \
  -H "X-AgentScope-Tenant: $TENANT" \
  -H "X-AgentScope-Namespace: $NAMESPACE"
```

<Accordion title="External tool results and designated approval">

Use real `output` only for an action asking for external tool results. Set `is_error: true` and report the actual error if execution failed. This is a different answer from a tool permission decision.

```bash
curl -sS --fail-with-body "$SESSION_URL/turns/$TURN_ID/actions" \
  -H "Authorization: Bearer $TOKEN" \
  -H "X-AgentScope-Tenant: $TENANT" \
  -H "X-AgentScope-Namespace: $NAMESPACE" \
  -H "Content-Type: application/json" \
  -H "Idempotency-Key: tool-result-001" \
  --data-binary @- <<JSON
{
  "request_id": "$REQUEST_ID",
  "payload": {
    "output": "Actual tool execution result",
    "is_error": false
  }
}
JSON
```

For a business approval, use the current version from the pending action and the designated approver’s `TOKEN`. Replace `EXPECTED_VERSION` with the actual number; use `decision: "rejected"` to reject it.

```bash
REQUEST_ID="BUSINESS_APPROVAL_REQUEST_ID"
EXPECTED_VERSION=1
curl -sS --fail-with-body "$SESSION_URL/turns/$TURN_ID/actions" \
  -H "Authorization: Bearer $TOKEN" \
  -H "X-AgentScope-Tenant: $TENANT" \
  -H "X-AgentScope-Namespace: $NAMESPACE" \
  -H "Content-Type: application/json" \
  -H "Idempotency-Key: business-approval-001" \
  --data-binary @- <<JSON
{
  "request_id": "$REQUEST_ID",
  "expected_version": $EXPECTED_VERSION,
  "decision": "approved"
}
JSON
```

</Accordion>

## Cancel and resume

Cancellation and resumption operate on an existing Turn and require stable idempotency keys. After a cancellation receipt, wait for an explicit terminal state. Cancellation cannot guarantee reversal of operations already sent to external systems, so reconcile their outcomes before another execution.

```bash
CANCEL_JSON=$(
  curl -sS --fail-with-body -X POST "$SESSION_URL/turns/$TURN_ID/cancel" \
    -H "X-API-Key: $AGENTSCOPE_API_KEY" \
    -H "Idempotency-Key: cancel-001"
)
CANCEL_COMMAND_ID=$(jq -er '.command.id' <<< "$CANCEL_JSON")
```

When `available_commands` from `/turns/{turnId}/capabilities` includes `resume`, submit to that Turn's `/resume` path. Resumption retains the Turn ID and continues from persisted state. Answer pending actions first, and reconcile uncertain tool results before continuing. Resume is not a general replacement for submitting a new task.

```bash
RESUME_JSON=$(
  curl -sS --fail-with-body -X POST "$SESSION_URL/turns/$TURN_ID/resume" \
    -H "X-API-Key: $AGENTSCOPE_API_KEY" \
    -H "Idempotency-Key: resume-001"
)
RESUME_COMMAND_ID=$(jq -er '.command.id' <<< "$RESUME_JSON")
```

After cancellation or resumption, read the corresponding command and then the Turn state. For example, inspect the cancellation above:

```bash
curl -sS --fail-with-body "$SESSION_URL/turns/$TURN_ID/commands/$CANCEL_COMMAND_ID" \
  -H "X-API-Key: $AGENTSCOPE_API_KEY"
```

```bash
curl -sS --fail-with-body "$SESSION_URL/turns/$TURN_ID" \
  -H "X-API-Key: $AGENTSCOPE_API_KEY"
```

<span id="budgets"></span>

## Usage, subagents, and budgets

A Managed Session's `/subagents` resource lists associated child sessions. Follow those associations to inspect child snapshots and events. Each child has its own cursor. `GET /usage?include_children=true` aggregates reported subtree usage, but does not represent an atomic snapshot of every child at the same moment.

```bash
curl -sS --fail-with-body -G "$SESSION_URL/usage" \
  -H "X-API-Key: $AGENTSCOPE_API_KEY" \
  --data-urlencode "include_children=true"
```

```bash
curl -sS --fail-with-body "$SESSION_URL/turns/$TURN_ID/usage" \
  -H "X-API-Key: $AGENTSCOPE_API_KEY"
```

<Accordion title="Inspect a subagent’s progress">

Read the associations and select a child session ID from the response. The snapshot and stream below both belong to that child:

```bash
curl -sS --fail-with-body "$SESSION_URL/subagents" \
  -H "X-API-Key: $AGENTSCOPE_API_KEY"
```

```bash
CHILD_SESSION_ID="CHILD_SESSION_ID_FROM_RESPONSE"
CHILD_URL="$SESSION_URL/subagents/$CHILD_SESSION_ID"
CHILD_SNAPSHOT=$(
  curl -sS --fail-with-body "$CHILD_URL/snapshot" \
    -H "X-API-Key: $AGENTSCOPE_API_KEY"
)
CHILD_CURSOR=$(jq -er '.as_of' <<< "$CHILD_SNAPSHOT")
```

```bash
curl -sS --fail-with-body -N -G "$CHILD_URL/events/stream" \
  -H "X-API-Key: $AGENTSCOPE_API_KEY" \
  -H "Accept: text/event-stream" \
  --data-urlencode "after=$CHILD_CURSOR"
```

</Accordion>

Use `/budget` to limit Managed model calls or accumulated usage. This example allows up to 30 model calls and checks the reported token total before further execution:

```bash
curl -sS --fail-with-body -X PUT "$SESSION_URL/budget" \
  -H "X-API-Key: $AGENTSCOPE_API_KEY" \
  -H "Content-Type: application/json" \
  --data-binary @- <<'JSON'
{
  "max_model_calls": 30,
  "max_total_tokens": 100000
}
JSON
```

Token and cost budgets use reported usage and cannot forcibly truncate parallel calls that have already started. Cost limits also require model pricing. Application concurrency and token budgets govern the application's overall workload separately from Managed runtime budgets. See the [Session API guide](/v2/en/service/service-api).

Read the Managed Session’s current budget and reported usage:

```bash
curl -sS --fail-with-body "$SESSION_URL/budget" \
  -H "X-API-Key: $AGENTSCOPE_API_KEY"
```

## Continue from a checkpoint

A Managed Session's `/checkpoints` resource lists restorable context records. Select a returned `checkpoint_id`, call `/checkpoints/restore`, and submit a new Turn. Restoration appends an audit fact, preserves history, and does not undo external effects.

```bash
curl -sS --fail-with-body -G "$SESSION_URL/checkpoints" \
  -H "X-API-Key: $AGENTSCOPE_API_KEY" \
  --data-urlencode "limit=20"
```

Fill in a returned checkpoint ID and restore only when the Session is idle and eligible:

```bash
CHECKPOINT_ID="CHECKPOINT_ID_FROM_RESPONSE"
curl -sS --fail-with-body "$SESSION_URL/checkpoints/restore" \
  -H "X-API-Key: $AGENTSCOPE_API_KEY" \
  -H "Content-Type: application/json" \
  -H "Idempotency-Key: restore-context-001" \
  --data-binary @- <<JSON
{
  "checkpoint_id": "$CHECKPOINT_ID",
  "reason": "Return to the context before verification for another attempt."
}
JSON
```

To keep the original conversation and experiment separately, create an empty Session for the same Agent, then call `/fork` on the source with `{target_session_id, checkpoint_id, reason}` and an idempotency key. The sessions must be idle and eligible for recovery, without queued tasks, unresolved actions, or uncertain tool results. Fork copies Agent state, not the environment, credentials, working files, or budget.

<Accordion title="Create an empty Session and fork">

```bash
TARGET_SESSION_JSON=$(
  curl -sS --fail-with-body "$BASE_URL/api/v1/agent-sessions" \
    -H "X-API-Key: $AGENTSCOPE_API_KEY" \
    -H "Content-Type: application/json" \
    -H "Idempotency-Key: fork-target-001" \
    --data-binary @- <<JSON
{
  "target": {
    "type": "agent",
    "id": "$AGENT_ID"
  }
}
JSON
)
TARGET_SESSION_ID=$(jq -er '.id' <<< "$TARGET_SESSION_JSON")
```

```bash
curl -sS --fail-with-body "$SESSION_URL/fork" \
  -H "X-API-Key: $AGENTSCOPE_API_KEY" \
  -H "Content-Type: application/json" \
  -H "Idempotency-Key: fork-context-001" \
  --data-binary @- <<JSON
{
  "target_session_id": "$TARGET_SESSION_ID",
  "checkpoint_id": "$CHECKPOINT_ID",
  "reason": "Keep the original record and experiment in a new Session."
}
JSON
```

Submit subsequent tasks to `"$BASE_URL/api/v1/agent-sessions/$TARGET_SESSION_ID/turns"`.

</Accordion>

Session `/archive` blocks new submissions and `/restore` unarchives it; neither restores a checkpoint. Deleting a Session removes application access but does not immediately erase underlying audit data. See [Operations](/v2/en/service/operations) for backup and retention.

<Accordion title="Archive, unarchive, and delete">

Ensure no active or queued Turns remain before archiving or deleting; otherwise the request returns 409. Choose the operation you need below. Restore only applies to an archived Session. Deletion removes application access.

Archive:

```bash
curl -sS --fail-with-body -X POST "$SESSION_URL/archive" \
  -H "X-API-Key: $AGENTSCOPE_API_KEY"
```

Unarchive:

```bash
curl -sS --fail-with-body -X POST "$SESSION_URL/restore" \
  -H "X-API-Key: $AGENTSCOPE_API_KEY"
```

Delete:

```bash
curl -sS --fail-with-body -X DELETE "$SESSION_URL" \
  -H "X-API-Key: $AGENTSCOPE_API_KEY"
```

</Accordion>

## Background notifications and clients

Register a Session or Turn Webhook for backend notifications after users leave the page. See [Webhooks](/v2/en/service/sse-events#webhooks) for signatures and retry behavior. Managed `/export` provides public execution records as JSONL.

```bash
curl -sS --fail-with-body "$SESSION_URL/export" \
  -H "X-API-Key: $AGENTSCOPE_API_KEY" \
  --output session-events.jsonl
```

The console uses `frontend/src/api/agentSessions.ts` for detailed Managed resources and `serviceSessions.ts` for generic task views. Both use the same Session API. Keep snapshots paired with their event resource and treat returned IDs as opaque values.

To connect these operations to your own chat page, continue with [Integration example: resumable chat](/v2/en/service/agent-api-chat). It combines task submission, progress display, and user actions, including how to continue the same work after leaving or refreshing the page.
