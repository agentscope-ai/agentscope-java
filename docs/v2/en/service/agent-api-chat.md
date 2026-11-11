---
title: "Example: a resumable chat application"
description: Connect a Managed Agent to a chat page through the Session API, including progress, refresh recovery, and human interaction.
zh_link: /v2/zh/service/agent-api-chat
---

This example connects a Managed notes assistant to a business chat page. After a user submits a task, the page shows the Agent's replies and tool calls. When execution requires confirmation, the page lets the user inspect the request and decide. Returning to the conversation restores saved messages, tool results, and pending actions before following further progress.

This example uses the same interface introduced in [Integrate applications with the Session API](/v2/en/service/service-api). It selects a Managed Agent to demonstrate continuous conversation and interaction during execution. Teams, Workflows, and other Agent types use the same entry point to create Sessions and submit Turns; the target's capabilities determine which content and controls an application can provide.

First, [create a Managed Agent](/v2/en/service/create-managed-agent), configure its model and Environment, and verify that it can answer. To try tool calls, bind usable tools following the [Tools guide](/v2/en/service/tools). For user confirmation, also set the relevant tool's `permissionPolicy.type` to `always_ask`. Without tools, you can still start with text conversation and refresh recovery.

## 1. Create a session and submit work

Use curl, jq and a [user login token](/v2/en/service/api-reference#authentication-and-scope) to test the same interaction as the local console. A business backend can instead use the authorized Application key from the integration guide. Substitute actual resource IDs; omit `environmentId` from the creation request if the Agent has a default Environment. Local Gateway defaults to port 18080.

```bash
set -euo pipefail
export BASE_URL="http://localhost:18080"
export TOKEN="YOUR_USER_TOKEN"
export TENANT="YOUR_TENANT"
export NAMESPACE="YOUR_NAMESPACE"
export AGENT_ID="YOUR_MANAGED_AGENT_ID"
export ENVIRONMENT_ID="YOUR_ENVIRONMENT_ID"
```

Create the conversation’s Session:

```bash
SESSION_JSON=$(
  curl -sS --fail-with-body "$BASE_URL/api/v1/agent-sessions" \
    -H "Authorization: Bearer $TOKEN" \
    -H "X-AgentScope-Tenant: $TENANT" \
    -H "X-AgentScope-Namespace: $NAMESPACE" \
    -H "Content-Type: application/json" \
    -H "Idempotency-Key: notes-session-001" \
    --data-binary @- <<JSON
{
  "target": {
    "type": "agent",
    "id": "$AGENT_ID"
  },
  "environmentId": "$ENVIRONMENT_ID"
}
JSON
)
SESSION_ID=$(jq -er '.id' <<< "$SESSION_JSON")
SESSION_URL="$BASE_URL/api/v1/agent-sessions/$SESSION_ID"
```

Submit the first question:

```bash
TURN_KEY="notes-chat-001"
TURN_JSON=$(
  curl -sS --fail-with-body "$SESSION_URL/turns" \
    -H "Authorization: Bearer $TOKEN" \
    -H "X-AgentScope-Tenant: $TENANT" \
    -H "X-AgentScope-Namespace: $NAMESPACE" \
    -H "Content-Type: application/json" \
    -H "Idempotency-Key: $TURN_KEY" \
    --data-binary @- <<'JSON'
{
  "message": "Organize meeting actions: Alex finishes the installation guide Friday. Review is Monday; time unconfirmed. List the information still needed."
}
JSON
)
TURN_ID=$(jq -er '.id' <<< "$TURN_JSON")
```

After creation, save `SESSION_ID` so the chat page route can identify this conversation, and save `TURN_ID` to identify the submitted task. Create a new Session only when the user starts a new conversation. On refresh, read the existing records rather than creating a conversation or sending the question again.

The two example idempotency keys identify Session creation and task submission separately. After a network timeout, retry the same operation with its original key and unchanged request body. Generate a new key when the user actually starts another conversation or submits the next question.

## 2. Restore content before subscribing

```bash
SNAPSHOT=$(
  curl -sS --fail-with-body "$SESSION_URL/snapshot" \
    -H "Authorization: Bearer $TOKEN" \
    -H "X-AgentScope-Tenant: $TENANT" \
    -H "X-AgentScope-Namespace: $NAMESPACE"
)
jq '{items, tools, turns, required_actions}' <<< "$SNAPSHOT"
CURSOR=$(jq -er '.as_of' <<< "$SNAPSHOT")
```

```bash
curl -sS --fail-with-body -N -G "$SESSION_URL/events/stream" \
  -H "Authorization: Bearer $TOKEN" \
  -H "X-AgentScope-Tenant: $TENANT" \
  -H "X-AgentScope-Namespace: $NAMESPACE" \
  -H "Accept: text/event-stream" \
  --data-urlencode "after=$CURSOR"
```

Ctrl-C closes the subscription while background work continues. Running this block again restores messages and tool results saved during disconnection, then follows new events from the snapshot's cursor. The refreshed page can display earlier content and continue following execution that has not finished.

A Session SSE connection can stay open across multiple Turns, so closing it does not establish task completion. Use the target `turn_id` and its `turn.completed` event or queried Turn state to determine success. See [SSE and event replay](/v2/en/service/sse-events) for deduplication, reconnects, and backend notifications.

```bash
curl -sS --fail-with-body "$SESSION_URL/turns/$TURN_ID" \
  -H "Authorization: Bearer $TOKEN" \
  -H "X-AgentScope-Tenant: $TENANT" \
  -H "X-AgentScope-Namespace: $NAMESPACE"
```

## 3. Connect your page

The console Session **Execution** tab already connects these interactions, using `agentscope-service/frontend/src/components/SessionExecution.tsx`. Submit a task, inspect tool calls, and answer pending actions there to understand a complete interaction before adding the same capabilities to your own page.

The following connection code can live under Console `src/`. It uses `api/agentSessions.ts` to read snapshots and events, then `api/agentSessionView.ts` to apply events to page state. These files are console client implementations. When moving them into another application, also adapt the `api/http.ts` authentication dependency and session types to your Gateway and login flow.

```typescript
import {
  AgentStreamError, getAgentSessionSnapshot, streamAgentSession,
} from './api/agentSessions';
import { AgentSessionView } from './api/agentSessionView';
import type { AgentSessionSnapshot } from './api/agentSessions';

export function mountConversation(
  sessionId: string,
  render: (snapshot: AgentSessionSnapshot) => void,
  showError: (error: unknown) => void,
): () => void {
  const controller = new AbortController();
  const { signal } = controller;
  const follow = async () => {
    // Reload once if the stream rejects a stale cursor.
    for (let attempt = 0; attempt < 2 && !signal.aborted; attempt++) {
      const snapshot = await getAgentSessionSnapshot(sessionId, signal);
      if (signal.aborted) return;
      const view = new AgentSessionView(snapshot);
      render(view.snapshot());
      try {
        await streamAgentSession(sessionId, {
          after: snapshot.as_of, signal,
          onEvent(event) {
            if (signal.aborted) return;
            view.apply(event);
            render(view.snapshot());
          },
        });
        return;
      } catch (error) {
        if (signal.aborted) return;
        if (attempt === 0 && error instanceof AgentStreamError
            && [400, 409, 410].includes(error.status)) continue;
        throw error;
      }
    }
  };
  void follow().catch(error => { if (!signal.aborted) showError(error); });
  return () => controller.abort();
}
```

When opening a conversation, call `mountConversation(sessionId, render, showError)` to restore content and subscribe to further events. Before leaving the page or switching conversations, call the returned cleanup function to stop receiving the old Session's events. The `render` callback updates existing cards by message and tool call ID so each response or tool call continues in the same place.

The client reconnects brief network interruptions from the last successfully applied cursor. A refresh loads a new snapshot. Storing only a cursor in localStorage without the corresponding view loses the prefix. Authentication and other non-recoverable request errors reach showError for the page to handle.

| UI area | Read / update rule |
| --- | --- |
| Messages | data.item in snapshot.items; update by item_id, replace content on item.completed |
| Tool cards | snapshot.tools; update arguments, progress, result and status by turn_id + tool_call_id |
| Pending actions | snapshot.required_actions; keep request_id, turn_id and kind |
| Task status | snapshot.turns or target turn events; run.ended ends only an attempt |
| Artifacts and children | snapshot.artifacts / subagents; expand children using their own snapshots and events |

Refresh can occur while tool arguments are still being generated. The reducer uses snapshot's active_tool_call_id to attach later fragments without a call ID. A task can produce multiple assistant items and tool calls; do not append every delta to the last message.

## 4. Wire user actions to commands

The following table connects chat page actions to the Session API. Paths are relative to `SESSION_URL`. Use Session, Turn, and request IDs returned by the service to identify existing work; Service manages the execution process.

Read current Turn capabilities before displaying controls. Choose the following operations according to user intent rather than running them in sequence:

```bash
curl -sS --fail-with-body "$SESSION_URL/turns/$TURN_ID/capabilities" \
  -H "Authorization: Bearer $TOKEN" \
  -H "X-AgentScope-Tenant: $TENANT" \
  -H "X-AgentScope-Namespace: $NAMESPACE"
```

| Button / scenario | Request | Next step |
| --- | --- | --- |
| Send a new question | POST `/turns`, `{message}` | New turn and key, same session stream |
| Correct the current task | POST `/turns/{turn}/steer`, `{message}` | Wait for input.applied; reload task state after 409 |
| Add context only | POST `/inputs/inject`, `{message}` | Does not wake an idle Agent; later steps consume it |
| Allow / deny a tool | POST `/turns/{turn}/actions` | Build an answer for the pending action; follow command status and action changes |
| Stop | POST `/turns/{turn}/cancel` | Wait for the explicit turn outcome; cancel_requested is not stopped |
| Continue interrupted work | POST `/turns/{turn}/resume` | Resolve pending actions/unknown tool results first; same turn, possibly a new run |

<AccordionGroup>

<Accordion title="Correct requirements or add context">

Correct a running task:

```bash
curl -sS --fail-with-body "$SESSION_URL/turns/$TURN_ID/steer" \
  -H "Authorization: Bearer $TOKEN" \
  -H "X-AgentScope-Tenant: $TENANT" \
  -H "X-AgentScope-Namespace: $NAMESPACE" \
  -H "Content-Type: application/json" \
  -H "Idempotency-Key: notes-correction-001" \
  --data-binary @- <<'JSON'
{
  "message": "List open questions first; do not write files yet."
}
JSON
```

Save context without starting inference:

```bash
curl -sS --fail-with-body "$SESSION_URL/inputs/inject" \
  -H "Authorization: Bearer $TOKEN" \
  -H "X-AgentScope-Tenant: $TENANT" \
  -H "X-AgentScope-Namespace: $NAMESPACE" \
  -H "Content-Type: application/json" \
  -H "Idempotency-Key: notes-context-001" \
  --data-binary @- <<'JSON'
{
  "message": "Additional context: the installation guide maintainer also joins the review."
}
JSON
```

</Accordion>

<Accordion title="Stop the current task">

```bash
CANCEL_JSON=$(
  curl -sS --fail-with-body -X POST "$SESSION_URL/turns/$TURN_ID/cancel" \
    -H "Authorization: Bearer $TOKEN" \
    -H "X-AgentScope-Tenant: $TENANT" \
    -H "X-AgentScope-Namespace: $NAMESPACE" \
    -H "Idempotency-Key: notes-cancel-001"
)
CANCEL_COMMAND_ID=$(jq -er '.command.id' <<< "$CANCEL_JSON")
```

Read the cancellation command, then the Turn state to confirm termination:

```bash
curl -sS --fail-with-body "$SESSION_URL/turns/$TURN_ID/commands/$CANCEL_COMMAND_ID" \
  -H "Authorization: Bearer $TOKEN" \
  -H "X-AgentScope-Tenant: $TENANT" \
  -H "X-AgentScope-Namespace: $NAMESPACE"
```

```bash
curl -sS --fail-with-body "$SESSION_URL/turns/$TURN_ID" \
  -H "Authorization: Bearer $TOKEN" \
  -H "X-AgentScope-Tenant: $TENANT" \
  -H "X-AgentScope-Namespace: $NAMESPACE"
```

</Accordion>

<Accordion title="Continue resumable work">

Resume only when `available_commands` includes `resume` and pending actions and uncertain tool results are resolved:

```bash
RESUME_JSON=$(
  curl -sS --fail-with-body -X POST "$SESSION_URL/turns/$TURN_ID/resume" \
    -H "Authorization: Bearer $TOKEN" \
    -H "X-AgentScope-Tenant: $TENANT" \
    -H "X-AgentScope-Namespace: $NAMESPACE" \
    -H "Idempotency-Key: notes-resume-001"
)
RESUME_COMMAND_ID=$(jq -er '.command.id' <<< "$RESUME_JSON")
```

```bash
curl -sS --fail-with-body "$SESSION_URL/turns/$TURN_ID/commands/$RESUME_COMMAND_ID" \
  -H "Authorization: Bearer $TOKEN" \
  -H "X-AgentScope-Tenant: $TENANT" \
  -H "X-AgentScope-Namespace: $NAMESPACE"
```

</Accordion>

</AccordionGroup>

Provide a stable `Idempotency-Key` when creating a Turn, changing requirements, adding context, or answering a pending action. The example below submits confirmation after the user inspects the tool request and chooses “Allow”. `REQUEST_ID` must come from the pending card's `request_id`; a tool call ID cannot replace it:

```bash
curl -sS --fail-with-body "$SESSION_URL/turns/$TURN_ID/actions" \
  -H "Authorization: Bearer $TOKEN" \
  -H "X-AgentScope-Tenant: $TENANT" \
  -H "X-AgentScope-Namespace: $NAMESPACE"
```

```bash
REQUEST_ID="REQUEST_ID_FROM_PENDING_ACTION"
COMMAND_JSON=$(
  curl -sS --fail-with-body "$SESSION_URL/turns/$TURN_ID/actions" \
    -H "Authorization: Bearer $TOKEN" \
    -H "X-AgentScope-Tenant: $TENANT" \
    -H "X-AgentScope-Namespace: $NAMESPACE" \
    -H "Content-Type: application/json" \
    -H "Idempotency-Key: notes-approval-001" \
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

After the answer is accepted, follow the returned command status and observe whether the action has been handled and execution continues. A successful HTTP request only means the service received this operation; it does not immediately resolve the pending action. If the command fails or the action remains pending, reload the latest records before asking the user to decide whether to retry.

```bash
curl -sS --fail-with-body "$SESSION_URL/turns/$TURN_ID/commands/$COMMAND_ID" \
  -H "Authorization: Bearer $TOKEN" \
  -H "X-AgentScope-Tenant: $TENANT" \
  -H "X-AgentScope-Namespace: $NAMESPACE"
```

For an action requesting external tool execution, submit the actual `output` and `is_error` inside `payload`. An identity-bound confirmation requires the designated person's authorized user identity. See [Answer a required action](/v2/en/service/session-event-log#answer-a-required-action) for locating the action and following command receipts. These answers advance the existing task; its Turn result still determines completion.

## 5. Exercise the flow with real tools

To verify the full interaction, bind at least two usable tool operations and require user confirmation for one. Submit a task matching their capabilities, such as “Read two documents, check each and write a summary”. While the Agent calls tools and prepares its result, try the page operations below and check that the application restores earlier content and follows subsequent execution.

| Try | Expected behavior |
| --- | --- |
| Refresh while text streams | Restore its committed prefix, then continue the same item |
| Refresh while tool arguments stream | Continue updating the same tool card |
| Leave during a tool call and return later | Restore tools/results and subsequent assistant items produced while away |
| Refresh while confirmation is pending | Keep the action; answering continues the original turn |
| Disconnect SSE and inspect work | Background execution continues; no cancel is sent |
| Ask a follow-up after completion | New turn in the same session with history retained |

Page recovery above only restores the display of existing work. Restoring an earlier checkpoint changes Agent context and is followed by a new Turn. To continue the original interrupted task, check recovery conditions before using `resume`. See [Sessions, tasks, and budgets](/v2/en/service/session-event-log) for these execution operations.

When the page also needs file upload or result download, add those capabilities with [Files and artifacts](/v2/en/service/files). To notify your business backend after users leave the page, configure Webhooks in [SSE and event replay](/v2/en/service/sse-events#webhooks).
