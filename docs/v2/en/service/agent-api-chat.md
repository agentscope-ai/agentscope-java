---
title: "Example: a resumable chat application"
description: Connect a Managed Agent to a chat page through the Session API, including progress, refresh recovery, and human interaction.
zh_link: /v2/zh/service/agent-api-chat
---

This example connects a Managed notes assistant to a business chat page. After a user submits a task, the page shows the Agent's replies and tool calls. When execution requires confirmation, the page lets the user inspect the request and decide. Returning to the conversation restores saved messages, tool results, and pending actions before following further progress.

This example uses the native Managed `/v1/sessions` API. It pins one Managed Agent and its Environment; see [Managed Session, Turn and Input API](/v2/en/service/claude-session-api) for the contract. Other execution backends use their own admission and cursor protocols.

First, [create a Managed Agent](/v2/en/service/create-managed-agent), configure its model and Environment, and verify that it can answer. To try tool calls, bind usable tools following the [Tools guide](/v2/en/service/tools). For user confirmation, also set the relevant tool's `permissionPolicy.type` to `always_ask`. Without tools, you can still start with text conversation and refresh recovery.

## 1. Create a session and submit work

Use curl and jq in local development mode without authentication headers. Substitute actual resource IDs; omit `environment_id` if the Agent has a default Environment. Local Gateway defaults to port 18080. See [production deployment](/v2/en/service/kubernetes#production-api-access) for identities.

```bash
set -euo pipefail
export BASE_URL="http://localhost:18080"
export AGENT_ID="YOUR_MANAGED_AGENT_ID"
export ENVIRONMENT_ID="YOUR_ENVIRONMENT_ID"
```

Create the conversation’s Session:

```bash
SESSION_JSON=$(
  curl -sS --fail-with-body "$BASE_URL/v1/sessions" \
    -H "Content-Type: application/json" \
    -H "Idempotency-Key: notes-session-001" \
    --data-binary @- <<JSON
{
  "agent": "$AGENT_ID",
  "environment_id": "$ENVIRONMENT_ID"
}
JSON
)
SESSION_ID=$(jq -er '.id' <<< "$SESSION_JSON")
SESSION_URL="$BASE_URL/v1/sessions/$SESSION_ID"
```

Submit the first question:

```bash
TURN_KEY="notes-chat-001"
TURN_JSON=$(
  curl -sS --fail-with-body "$SESSION_URL/turns" \
    -H "Content-Type: application/json" \
    -H "Idempotency-Key: $TURN_KEY" \
    --data-binary @- <<'JSON'
{
  "input": {"message": "Organize meeting actions: Alex finishes the installation guide Friday. Review is Monday; time unconfirmed. List the information still needed."}
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
  curl -sS --fail-with-body "$SESSION_URL/snapshot"
)
jq '{snapshot_id, as_of, resources}' <<< "$SNAPSHOT"
CURSOR=$(jq -er '.as_of' <<< "$SNAPSHOT")
```

```bash
curl -sS --fail-with-body -N -G "$SESSION_URL/events/stream" \
  -H "Accept: text/event-stream" \
  --data-urlencode "after=$CURSOR"
```

Ctrl-C closes the subscription while background work continues. Running this block again restores messages and tool results saved during disconnection, then follows new events from the snapshot's cursor. The refreshed page can display earlier content and continue following execution that has not finished.

A Session SSE connection can stay open across multiple Turns, so closing it does not establish task completion. Use the target `turn_id` and its `turn.completed` event or queried Turn state to determine success. See [SSE and event replay](/v2/en/service/sse-events) for deduplication, reconnects, and backend notifications.

```bash
curl -sS --fail-with-body "$SESSION_URL/turns/$TURN_ID"
```

The HTTP snapshot contains bounded pages under `resources.<kind>.data`. Continue every needed page with the same `snapshot_id` through `/resources/{kind}`. The Console helper below flattens these first pages into its own view type; its `snapshot.items` is not the raw HTTP shape.

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

## 4. Submit interactions as Turn Inputs

Read `$SESSION_URL/turns/$TURN_ID/capabilities` and the Turn's `inputs_open` before displaying controls. The native API accepts all four interaction types through the same route:

| User intent | Input body | What confirms application |
| --- | --- | --- |
| Correct the current task | `{"type":"steer","payload":{"message":"New requirement"}}` | Input receipt reaches `applied` |
| Add context to this Turn | `{"type":"inject","payload":{"message":"Additional context"}}` | Input is applied at a safe boundary or explicitly rejected |
| Answer a required action | `{"type":"reply","action_id":"REQUEST_ID","payload":{...}}` | Reply is applied and the action is resolved |
| Stop the current task | `{"type":"cancel","payload":{}}` | The Turn reaches its actual terminal state |
| Ask a new question | Submit another `POST /turns` with `{"input":{"message":"Next question"}}` | A new Turn is admitted in the same Session |

For example, correct an open Turn and retain its Input receipt:

```bash
INPUT_JSON=$(
  curl -sS --fail-with-body "$SESSION_URL/turns/$TURN_ID/inputs" \
    -H "Content-Type: application/json" \
    -H "Idempotency-Key: notes-correction-001" \
    --data-binary '{"type":"steer","payload":{"message":"List open questions first; do not write files yet."}}'
)
INPUT_ID=$(jq -er '.id' <<< "$INPUT_JSON")
curl -sS --fail-with-body "$SESSION_URL/turns/$TURN_ID/inputs/$INPUT_ID"
```

`accepted` means durable admission, and `delivery_status=delivered` is transport progress. Only `status=applied` confirms runtime application. A rejected Input retains a reason. Reuse the same key and body after a timeout. A new intent requires a new key. Inputs do not create another Turn, and steering cannot alter a model request already sent.

Read `resources.required_actions.data` from the snapshot. After the user chooses a response, use that card's actual `data.request_id` as `action_id`:

```bash
ACTION_ID="REQUEST_ID_FROM_PENDING_ACTION"
REPLY_JSON=$(
  curl -sS --fail-with-body "$SESSION_URL/turns/$TURN_ID/inputs" \
    -H "Content-Type: application/json" \
    -H "Idempotency-Key: notes-answer-001" \
    --data-binary @- <<JSON
{"type":"reply","action_id":"$ACTION_ID","payload":{"allow":true,"reason":"Confirmed by the user"}}
JSON
)
REPLY_ID=$(jq -er '.id' <<< "$REPLY_JSON")
curl -sS --fail-with-body "$SESSION_URL/turns/$TURN_ID/inputs/$REPLY_ID"
```

A confirmation uses `allow`; a request for external execution uses actual `content` and `is_error`, as described in [required action replies](/v2/en/service/session-event-log#answer-a-required-action). Answering does not establish completion; continue observing the same Turn. General external-result and persistent input-wait acceptance remains part of the ongoing migration.

Cancellation uses the same endpoint:

```bash
curl -sS --fail-with-body "$SESSION_URL/turns/$TURN_ID/inputs" \
  -H "Content-Type: application/json" \
  -H "Idempotency-Key: notes-cancel-001" \
  --data-binary '{"type":"cancel","payload":{}}'
curl -sS --fail-with-body "$SESSION_URL/turns/$TURN_ID"
```

An accepted cancel is not proof that external work has stopped. Wait for `completed`, `failed` or `cancelled`. `waiting_tool` resumes automatically after Hands commits the result; it does not require the caller to supply a result or start another Turn. Current native capabilities declare `resume=false`, so the old explicit `/resume` and `/commands` flow is unavailable here.

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

Page recovery restores the display of existing work. Checkpoint restore and Fork remain disabled in native capabilities pending complete historical-state acceptance. Automatic long-tool continuation retains the original Turn; see [logs and recovery](/v2/en/service/session-event-log) for the current boundary.

When the page also needs file upload or result download, add those capabilities with [Files and artifacts](/v2/en/service/files). For background observation, consume durable SSE in your backend. Native webhook registration is still pending; the [Webhook guide](/v2/en/service/sse-events#webhooks) describes the existing backend protocol.
