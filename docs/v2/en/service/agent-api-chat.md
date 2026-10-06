---
title: "Example: a resumable chat application"
description: Connect multi-turn chat, tools, refresh recovery, human confirmation and task cancellation through the Managed native session API.
zh_link: /v2/zh/service/agent-api-chat
---

Connect a Managed “Notes assistant” to your application. The user submits material, the Agent uses tools and summarizes results, and returning to the same session restores committed messages, tool arguments, results and pending actions.

This example uses the Managed native session API to manage hosted sessions, messages, tools, and pending actions directly. To consume published services across Agent types or invoke Teams and Workflows, start with the [Unified service API](/v2/en/service/service-api).

[Create a Managed Agent](/v2/en/service/create-managed-agent), configure its model and Environment, and verify inference with the session request on that page. Tool examples require available tools; confirmation requires `permissionPolicy.type=always_ask` on the relevant tool. Without tools, start with text and refresh recovery.

## 1. Create a session and submit work

Use curl, jq and a [user login token](/v2/en/service/api-reference#authentication-and-scope). Local Gateway defaults to port 18080. Substitute actual resource IDs; omit environmentId if the Agent has a default Environment.

```bash
export BASE_URL='http://localhost:18080'
export TOKEN='YOUR_USER_TOKEN'
export AGENT_ID='YOUR_MANAGED_AGENT_ID'
export ENVIRONMENT_ID='YOUR_ENVIRONMENT_ID'

SESSION_JSON=$(curl --fail-with-body -sS "$BASE_URL/api/v1/agent-sessions" \
  -H "Authorization: Bearer $TOKEN" -H 'Content-Type: application/json' \
  -d "$(jq -n --arg agent "$AGENT_ID" --arg env "$ENVIRONMENT_ID" \
        '{agent:$agent, environmentId:$env}')")
export SESSION_ID=$(printf '%s' "$SESSION_JSON" | jq -er '.id')
export SESSION_URL="$BASE_URL/api/v1/agent-sessions/$SESSION_ID"

TURN_KEY='notes-chat-001'
TURN_JSON=$(curl --fail-with-body -sS "$SESSION_URL/turns" \
  -H "Authorization: Bearer $TOKEN" -H 'Content-Type: application/json' \
  -H "Idempotency-Key: $TURN_KEY" \
  -d '{"message":"Organize meeting actions: Alex finishes the installation guide Friday. Review is Monday; time unconfirmed. List the information still needed."}')
export TURN_ID=$(printf '%s' "$TURN_JSON" | jq -er '.id')
```

Keep SESSION_ID in the chat route and TURN_ID for this task. Create a session only for “New conversation”; refreshing does not create a session or resend input. New questions get new keys; retries of a submission keep its original key and input.

## 2. Restore content before subscribing

```bash
SNAPSHOT=$(curl --fail-with-body -sS "$SESSION_URL/snapshot" \
  -H "Authorization: Bearer $TOKEN")
printf '%s' "$SNAPSHOT" | jq '{items,tools,turns,required_actions}'
CURSOR=$(printf '%s' "$SNAPSHOT" | jq -er '.as_of')
curl --fail-with-body -N -G "$SESSION_URL/events/stream" \
  -H "Authorization: Bearer $TOKEN" -H 'Accept: text/event-stream' \
  --data-urlencode "after=$CURSOR"
```

Ctrl-C closes only the subscription. Run this block again to load messages and tools saved while disconnected, then follow new events. The already-generated prefix is restored too. SSE stays open across turns; success is the target turn's `turn.completed` event.

## 3. Connect your page

The complete console reference is `agentscope-service/frontend/src/components/SessionExecution.tsx`, in the Session **Execution** tab. It displays messages, tool cards, actions, file inputs and steer/inject. Try it before connecting your own UI.

The following connection code can live under Console `src/`. It reuses `api/agentSessions.ts` and `api/agentSessionView.ts`, which are repository client implementations, not a separately installed SDK. When moving them into another application, also adapt the `api/http.ts` authentication dependency and session types to your Gateway and login flow.

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
            && [400, 409].includes(error.status)) continue;
        throw error;
      }
    }
  };
  void follow().catch(error => { if (!signal.aborted) showError(error); });
  return () => controller.abort();
}
```

On mount or session change, call `mountConversation(sessionId, render, showError)`. Call its returned cleanup function before unmounting or switching. Render cards by message/tool identity. Session selection changes selection state while retaining list-query ordering.

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

Paths below are relative to SESSION_URL. Keep session/turn/request identities; your application does not allocate run IDs.

| Button / scenario | Request | Next step |
| --- | --- | --- |
| Send a new question | POST `/turns`, `{message}` | New turn and key, same session stream |
| Correct the current task | POST `/turns/{turn}/steer`, `{message}` | Wait for input.applied; reload task state after 409 |
| Add context only | POST `/inputs/inject`, `{message}` | Does not wake an idle Agent; later steps consume it |
| Allow / deny a tool | POST `/turns/{turn}/actions` | Build an answer for the pending kind; wait for resolved / rejected |
| Stop | POST `/turns/{turn}/cancel` | Wait for the explicit turn outcome; cancel_requested is not stopped |
| Continue interrupted work | POST `/turns/{turn}/resume` | Resolve pending actions/unknown tool results first; same turn, possibly a new run |

Turns, steer, inject and actions require a stable Idempotency-Key per logical submission. This confirmation example runs after the user chooses “Allow”. REQUEST_ID comes from the pending card, not the tool call ID:

```bash
REQUEST_ID='REQUEST_ID_FROM_PENDING_ACTION'
curl --fail-with-body -sS "$SESSION_URL/turns/$TURN_ID/actions" \
  -H "Authorization: Bearer $TOKEN" -H 'Content-Type: application/json' \
  -H 'Idempotency-Key: notes-approval-001' \
  -d "$(jq -n --arg request "$REQUEST_ID" \
        '{answers:[{request_id:$request,allow:true,reason:"Confirmed by user"}]}')"
```

`accepted` acknowledges receipt; `resolved` means runtime processing completed. `rejected` means delivery failed and restores the card if still pending. An `external_execution` action needs output/is_error; see [human interaction](/v2/en/service/session-event-log#answer-required-actions). A successful answer request does not mean the whole task is complete.

## 5. Exercise the flow with real tools

Bind at least two usable tool operations and require confirmation for one. Submit a task matching their capabilities, such as “Read two documents, check each and write a summary”. Actual tool latency and model calls determine streaming; a request to write slowly is not a tool-loop demonstration.

| Try | Expected behavior |
| --- | --- |
| Refresh while text streams | Restore its committed prefix, then continue the same item |
| Refresh while tool arguments stream | Continue updating the same tool card |
| Leave during a tool call and return later | Restore tools/results and subsequent assistant items produced while away |
| Refresh while confirmation is pending | Keep the action; answering continues the original turn |
| Disconnect SSE and inspect work | Background execution continues; no cancel is sent |
| Ask a follow-up after completion | New turn in the same session with history retained |

Restoring a checkpoint is a different operation: it changes Agent context and is followed by a new turn. It is neither page refresh nor continuation of the original task. For forks, file delivery, budgets and backend notifications, continue with the [Agent API guide](/v2/en/service/session-event-log). See the [SSE guide](/v2/en/service/sse-events) for the event catalog and error handling.
