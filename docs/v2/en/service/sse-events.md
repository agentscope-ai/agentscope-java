---
title: "SSE events and frontend integration"
description: Choose Invocation or Managed session events, display messages and orchestration progress, handle interactions, and reconnect.
zh_link: /v2/zh/service/sse-events
---

With AgentScope Service, submit work over HTTP and observe it over SSE. A task can produce several assistant messages, use many tools and pause for input. Closing SSE stops observation; stopping a task requires the cancel API.

For a published Agent, Team, or Workflow, obtain an Invocation through the [Unified service API](/v2/en/service/service-api). For direct Managed session control, follow the [Native session guide](/v2/en/service/session-event-log) and retain `SESSION_URL`, `TOKEN`, and `TURN_ID`. Select the event resource before connecting its snapshot and SSE.

## Choose the event contract

| Scenario | Entry point | Contract |
| --- | --- | --- |
| Managed Agent sessions | `/api/v1/agent-sessions/{id}/events/stream` | Durable session events below; user Bearer token |
| Published Endpoint invocations | The invocation response’s `eventsUrl` | Unified Invocation protocol below; Endpoint credentials |
| Agent in your own Java process | AgentSession / agent.streamEvents | [SDK guide](/v2/en/docs/harness/session-log), not HTTP SSE |

## Endpoint protocol scope

Published Agents, Teams and Workflows now use one **Invocation event protocol**. Follow snapshotUrl and eventsUrl returned by Job or Conversation submission. See the [unified Service API guide](/v2/en/service/service-api) for complete requests and event types.

| | Managed session API | Public Invocation API |
| --- | --- | --- |
| Identity | session_id, with native turn_id/run_id | invocation_id, member execution_id |
| Event route | `/api/v1/agent-sessions/{id}/events/stream` | `/invoke/v1/invocations/{id}/events/stream` |
| Snapshot resources | Arrays of event envelopes | Objects keyed by resource ID with accumulated data |
| Resumption | Session-specific opaque cursor | Invocation-specific opaque cursor |
| Interaction | Native turn steer/actions/cancel/resume | Invocation inputs/actions/cancel/resume |
| Recovery | UI and native session/checkpoint capabilities | Business invocation view; native checkpoints remain separate |

Both use snapshot + as_of + SSE. Their cursors and snapshot shapes are not interchangeable. Invocation shares item.*, tool.* and required_action.* semantics, adding invocation.*, step.updated / step.failed and command.* service events.

```text
id: <invocation-cursor>
event: tool.completed
data: {"schema_version":1,"id":"event-id","type":"tool.completed","invocation_id":"invocation-id","created_at":1790928000000,"cursor":"<invocation-cursor>","data":{"execution_id":"member-execution","tool_call_id":"lookup-1","output":[{"type":"text","text":"Checked"}]}}
```

Follow the returned Invocation URLs. Historical Job/Conversation event endpoints have been removed; all public observations use the Invocation snapshot and opaque event cursor. An expired cursor returns 410/cursor_expired: reload the snapshot and continue from its as_of. See serviceInvocations.ts for the TypeScript client/reducer and aistio/examples/service-api for a runnable example.

The following sections describe **Managed native session** events and frontend integration. Use the Invocation resources above for a published service.

## Connect a chat UI to the right operations

| User action or UI | HTTP operation (relative to the session URL) | Main events / state |
| --- | --- | --- |
| Send a new question | POST `/turns` | turn.accepted, turn.running, item.*, tool.*, then the target turn outcome |
| Open or refresh a session | GET `/snapshot`, then GET `/events/stream?after=as_of` | Restore the full view before applying deltas |
| Correct the current task | POST `/turns/{turn}/steer` | input.accepted, input.applied / input.rejected |
| Confirm a tool or supply an external result | POST `/turns/{turn}/actions` | required_action.accepted, resolved / rejected |
| Stop / continue execution | POST `/turns/{turn}/cancel` or `/resume` | turn.cancel_requested → explicit outcome; resume keeps the turn |
| Expand a child Agent | GET `/subagents/{child}/snapshot` and `/events/stream` | Child events with their own as_of |
| Download a deliverable | GET `/artifacts`, `/files/{file}/content` | artifact.published / artifact.deleted |
| Display usage / get offline notifications | GET `/usage`; register `/webhooks` | usage.recorded / budget.exceeded; read event details after a webhook |

The [resumable chat example](/v2/en/service/agent-api-chat) provides creation, submission and page lifecycle code. Route requests through Gateway with a user Bearer token, directly or through your application backend. Commands and SSE feedback use separate requests.

```mermaid
sequenceDiagram
    participant UI as Application UI
    participant API as Agent API
    participant Agent as Managed Agent
    UI->>API: POST turns (idempotency key)
    API-->>UI: 202 + turn ID
    API->>Agent: Execute in background
    UI->>API: GET snapshot
    API-->>UI: Messages, tools, actions + as_of
    UI->>API: GET events/stream?after=as_of
    Agent->>API: Persist message and tool events
    API-->>UI: Replay and stream events
    Note over UI,API: Disconnect only stops observation; reopen with a fresh snapshot
    API-->>UI: turn.completed / failed / requires_action, etc.
```

## Understand the identifiers

session_id scopes the conversation. turn_id identifies a task, run_id one attempt. A resume or action answer may start a new run in the same turn. item_id identifies a message; `(turn_id, tool_call_id)` identifies a tool card. Event id is for deduplication; opaque cursor is for resuming within one session.

Each model call retains one item_id across item.started, item.delta and item.completed. A turn may produce multiple independent assistant items. Never concatenate all turn deltas into one message.

## Load history, then follow new events

```bash
SNAPSHOT=$(curl --fail-with-body -sS "$SESSION_URL/snapshot" -H "Authorization: Bearer $TOKEN")
CURSOR=$(printf '%s' "$SNAPSHOT" | jq -er '.as_of')
curl --fail-with-body -N -G "$SESSION_URL/events/stream" \
  -H "Authorization: Bearer $TOKEN" --data-urlencode "after=$CURSOR"
```

Render snapshot first and apply events after as_of. Events committed between those requests are replayed. Snapshot resources share this watermark; embedded session metadata is current.

| Field | Meaning |
| --- | --- |
| items | Complete messages and committed partial prefixes in data.item |
| tools | Accumulated arguments, progress, outputs and status in data |
| turns / runs | Latest task states / execution attempts |
| required_actions | Pending requests, including still-pending rejected deliveries |
| action_commands / inputs | Submitted answers and steer/inject status |
| artifacts / subagents / usage | Published artifacts, child links and model usage |
| as_of | Starting cursor for the subsequent stream |

item.updated and tool.updated inside resource arrays are cumulative view envelopes, not new log event types. Initialize the UI directly from their data. This restores partial message/tool state without replaying the entire history.

### Durable frame and cursor

```text
id: <opaque-cursor>
event: item.delta
data: {"schema_version":1,"id":"e1","type":"item.delta","session_id":"s1","created_at":1790726400000,"cursor":"<opaque-cursor>","data":{"turn_id":"t1","run_id":"r1","item_id":"item_model_m1","model_call_id":"m1","position":8,"message_id":"msg1","content":[{"type":"text","text":"Checking"}]}}

```

SSE id equals JSON cursor; event equals JSON type. created_at is Unix milliseconds. Some administrative events omit turn/run identity. Internal native source references are excluded.

Advance the cursor only after applying an event and deduplicate event IDs. Supply after or Last-Event-ID; if both are supplied, the server validates both and uses the later position. Omit them to read from the beginning. Invalid/cross-session cursors return 400; ahead-of-head cursors return 409. Correct the request and reload snapshot. Comment heartbeats arrive every 15 seconds and never advance the cursor. EOF is not task completion.

### Restoring incremental content

item.delta and tool.delta are durable, replayable events enabled by default, including across replicas. The legacy preview flag remains accepted but does not change behavior. Only committed prefixes are promised; uncommitted provider output is not yet history.

Append text blocks to the matching item. tool_use.content contains argument fragments; group by call ID. Consecutive fragments without an ID inherit that item's active_tool_call_id, retained in snapshots. tool.requested/dispatched.input contains parsed arguments; tool.delta.output contains progress; tool.completed.result contains the final result. item.completed replaces accumulated content. Ignore duplicates and late deltas for completed items.

Tool cards and message cards are separate views. tool.completed and a message's tool_result block can describe the same call: update the existing card by call ID.

## Durable event catalog

### Task and execution state

| Event | Meaning |
| --- | --- |
| turn.accepted / turn.queued | Received / waiting for dispatch |
| turn.running | Dispatched; display the task as running |
| turn.requires_action | Waiting for confirmation or an external result |
| turn.interrupted / turn.failed | Interrupted / failed; handle the cause before resume |
| turn.cancel_requested / turn.cancelled | Stop requested / cancelled |
| turn.completed | Target logical task succeeded |
| run.started / run.ended | One attempt started/ended; status can include suspended, interrupted or failed |

Turn events carry turn_id; run events carry run_id and usually turn_id. An unfinished earlier turn blocks later turns in the same session. Resuming after answers emits turn.queued, replacing the stale requires_action state in snapshots.

### Messages and tools

| Event | Main fields | UI action |
| --- | --- | --- |
| item.started | item_id, item, model_call_id | Create an in_progress item |
| item.delta | item_id, position, content | Accumulate text/arguments after deduplication |
| item.completed | item_id, item, optional final_output | Replace the item; still wait for turn.completed |
| tool.requested | tool_call_id, name, input | Display the call and arguments |
| tool.dispatched | tool_call_id, name, input | Mark execution started |
| tool.delta | tool_call_id, output | Accumulate progress |
| tool.completed | tool_call_id, result, status | Show the final result; check result.state/status for success |
| model.completed | model_call_id, model, status | Model ended without reported usage |

Public blocks include text, image, audio, video, data, file, tool_use and tool_result. Raw thinking, system messages, private hint blocks and full request prompts are excluded. Tool authors decide which business output is appropriate to expose.

### Human input and external results

| Event | Main fields | Meaning |
| --- | --- | --- |
| required_action.created | request_id, kind, tool_call, turn_id | Pending confirmation/external_execution |
| required_action.accepted | request_id, command_id | Answer durably received |
| required_action.resolved | request_id, kind | Runtime processing complete |
| required_action.rejected | request_id, command_id, reason, pending | Delivery failed; pending=true restores kind/tool_call |

Rejected is not a user denial and accepted is not runtime approval. Restore from required_actions/action_commands. See [answering actions](/v2/en/service/session-event-log#answer-required-actions).

### Other execution information

| Event | Main fields and purpose |
| --- | --- |
| input.accepted / input.applied / input.rejected | input_id or input_ids, kind/status/reason; receipt versus consumption |
| usage.recorded | model_call_id, model, usage, scope; deduplicate per call |
| budget.exceeded | limit, model_call_id; model admission was refused |
| subagent.started / subagent.completed | childSessionId, childAgentId; inspect child public resources |
| artifact.published / artifact.deleted | artifact_id and file_id/uri publication metadata |
| context.compacted | Context changed without exposing its private contents |
| session.context_initialized | items and coverage=baseline_only for imported/forked context |
| session.restored | checkpoint_id, operation_id, reason; audit history is retained |
| session.status_created / session.status_running / session.status_idle | Session created, running or idle |
| session.status_rescheduled / session.status_requires_action | Session rescheduled or waiting for input |
| session.status_terminated / session.status_archived | Session terminated or archived |
| session.error / session.hint | Error or display hint; not a root turn outcome |

Consume cursors for unknown types and ignore unfamiliar display fields. Child Agents have independent streams and cursors. Use `/usage?include_children=true` explicitly for aggregate usage. Public events/export omit raw checkpoints; privileged inspection uses [trace](/v2/en/service/session-event-log#administrator-checkpoint-and-tool-reconciliation).

## A tool interaction from start to finish

```text
turn.accepted T1 → turn.running T1 → run.started R1
item.started M1 → item.delta M1 (text/tool arguments) → item.completed M1
required_action.created A1 → run.ended R1 suspended → turn.requires_action T1
Submit actions → required_action.accepted A1 → turn.queued T1
turn.running T1 → run.started R2 → required_action.resolved A1
tool.dispatched C1 → tool.delta C1 → tool.completed C1
item.started M2 → item.delta M2 → item.completed M2
run.ended R2 completed → turn.completed T1
```

This is a suspended interaction example. Online confirmation may remain in one run, and actual execution determines message/tool ordering. Returning at any point restores messages, tool cards and actions from snapshot before applying later events.

## Frontend integration

The Console fetch client supports Bearer headers, SSE parsing, reconnects and cursors. Native EventSource cannot directly set Authorization. This example runs within Console; copy the client and adapt authentication/URLs for a standalone app.

```typescript
import { getAgentSessionSnapshot, streamAgentSession } from './api/agentSessions';
import { AgentSessionView } from './api/agentSessionView';

const controller = new AbortController();
async function observe(sessionId: string) {
  const snapshot = await getAgentSessionSnapshot(sessionId, controller.signal);
  const view = new AgentSessionView(snapshot);
  render(view.snapshot());
  await streamAgentSession(sessionId, {
    after: snapshot.as_of, signal: controller.signal,
    onEvent(event) { view.apply(event); render(view.snapshot()); },
  });
}
function render(snapshot: ReturnType<AgentSessionView['snapshot']>) {
  // Update the application UI from snapshot.items/tools/required_actions.
  console.log(snapshot);
}
// Enter: observe(sessionId). Unmount: controller.abort().
```

The reducer merges full records/deltas, restores active tool arguments and handles rejected actions, children and artifacts. Reload snapshot after a full page refresh. Retaining a cursor without the corresponding UI state loses the content prefix. For brief disconnects, the client reconnects from its in-memory applied cursor. Aborting only closes the reader.

waitForAgentTurn returns explicit outcomes for the target task, including requires_action/interrupted for caller handling. After answers or resume successfully requeue the task, it can wait again for the same turn. A run ending within a tool loop is not success.

### Restoring a tool timeline

snapshot.tools supplies current accumulated cards. For every request, dispatch and progress update in original order, page through `/events`, deduplicate event IDs, then stream after the final next_cursor. Ordinary chat views only need snapshot plus the reducer. Long resource lists can use `/resources/{resource}` fixed-snapshot pagination.

## Connection errors

| Condition | Action |
| --- | --- |
| 401 / 403 | Repair authentication/access; avoid endless retry |
| 400 / 409 cursor error | Check session scope and reload snapshot |
| 410 resource page expiry | Restart pagination; never use its cursor for SSE |
| Network failure / EOF | Reconnect after the last applied event; do not create new work |
| No deltas | Inspect execution and heartbeats; models/tools may not emit incremental content |
| Run ended without turn outcome | Keep observing until the service commits the task result |

The HTTP contract is `agentscope-service/docs/agent-api/openapi-v1.json`; `public-event-v1.schema.json` in the same directory defines events. Implement clients against the AgentScope protocol documented here.
