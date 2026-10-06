---
title: "Unified Service API: Agents, Teams and Workflows"
description: Publish a service, submit work, restore its UI, handle interactions and retrieve results.
zh_link: /v2/zh/service/service-api
---

Publish an Endpoint to expose an Agent, Team or Workflow to your application. Each request creates an Invocation with the same status, snapshot, event and interaction APIs. The service manages internal Issues, task assignments and collaboration.

For in-process Java development, start with `agent.call` or `agent.streamEvents`, then use [AgentSession](/v2/en/docs/harness/session-log) for persistent conversations. For direct Managed session files, child sessions or checkpoint operations, use the [Managed Agent API](/v2/en/service/session-event-log).

## Resources and submission

| Resource | Purpose |
| --- | --- |
| Endpoint | Stable address and published input/output contract |
| Invocation | One logical request, its execution, events, actions and result |
| Conversation | A supported Agent's multi-turn session; each submission creates an Invocation |

Agents, Teams and Workflows support Jobs. Conversation requires a conversation-capable Agent. Read `GET /invoke/v1/endpoints/{slug}/capabilities` before offering operations in your application.

```bash
curl --fail-with-body "$BASE_URL/invoke/v1/endpoints/report/jobs" \
  -H "X-API-Key: $ENDPOINT_KEY" -H 'Content-Type: application/json' \
  -H 'Idempotency-Key: order-001' \
  --data '{"title":"Investigate order","input":{"request":"Check delivery progress"}}'
```

Publish the Endpoint using the [Endpoint guide](/v2/en/service/endpoints). Save `invocationId`, `statusUrl`, `snapshotUrl` and `eventsUrl`. A 202 response acknowledges durable acceptance. The background worker can finish materializing execution after a submitting connection or process exits. Retry the same logical request with the same key and body; a changed body with that key returns 409.

Read `invocation.status` and `invocation.result` from statusUrl. Active states are accepted, dispatching, running, waiting and cancel_requested. Terminal states are completed, partial_succeeded, failed, cancelled and timed_out. `partial_succeeded` exposes useful output with failed branches; inspect `steps` and handle it separately from full success. A member or tool completing is not the entire Team completing.

## Restore a UI and continue observing

1. GET snapshotUrl and render items, tools, required_actions, steps, artifacts and usage.
2. Connect to eventsUrl with `after=snapshot.as_of`.
3. If the in-memory view survives a disconnect, resume after its last applied cursor. If the view was lost, load another snapshot first.

Snapshot resources are objects keyed by identity, containing accumulated data. `items[item_id].item.content` includes the committed prefix of an unfinished message. Tool keys are `execution_id + ':' + tool_call_id`. Messages and tools completed while the client was away remain available.

```text
id: <opaque-cursor>
event: item.delta
data: {"schema_version":1,"id":"event-id","invocation_id":"invocation-id","type":"item.delta","created_at":1790928000000,"cursor":"<opaque-cursor>","data":{"execution_id":"execution-id","item_id":"message-id","content":[{"type":"text","text":"Checking"}]}}
```

Cursors are opaque and invocation-specific. Last-Event-ID takes precedence over after. JSON pagination uses `GET /invoke/v1/invocations/{id}/events?after=...&limit=100`, returning data, next_cursor and has_more. Ignore unknown types while advancing the cursor. SSE heartbeat comments are not business events.

| Events | Handling |
| --- | --- |
| invocation.accepted / dispatching / running / waiting | Overall progress |
| invocation.cancel_requested / completed / partial_succeeded / failed / cancelled / timed_out | Stopping or confirmed final outcome |
| item.started / delta / completed | Create, accumulate and replace messages by item_id |
| tool.requested / dispatched / delta / completed | Tool arguments, progress and results by execution and call ID |
| execution.ended | Mark unfinished messages incomplete and unconfirmed tools unknown |
| step.updated / step.failed | Member task progress |
| required_action.created / resolved | Pending business interactions |
| command.accepted / completed / failed | Saved command versus delivered or failed command |
| artifact.published / deleted | Published deliverables and download_url |
| usage.recorded / model.completed | Update usage by execution/model call; do not sum repeated records |
| budget.exceeded | Observed budget reached; cancellation requested |

Runtime event granularity varies. A runtime that reports complete messages does not automatically provide token deltas. Raw model reasoning and internal collaboration DTOs are outside this contract.

## Actions, input and cancellation

Paths below are relative to `/invoke/v1/invocations/{id}`. POST commands require Idempotency-Key and return a command with status_url.

| Operation | Request |
| --- | --- |
| Current actions | `GET /actions` |
| Answer Workflow signal | `POST /actions`: `{"request_id":"returned ID","expected_version":3,"payload":{"answer":"..."}}` |
| Approval | `POST /actions`: `{"request_id":"returned ID","expected_version":3,"decision":"approved","payload":{"reason":"verified"}}` |
| Additional requirements | `POST /inputs`: `{"message":"Prioritize paid orders"}` |
| Cancel | `POST /cancel`: `{}`; await the confirmed outcome |
| Resume paused Workflow / interrupted Managed turn | `POST /resume`: `{}`; answer actions for work waiting on input |
| Command status | `GET /commands/{commandId}` |

Only the designated approver can decide an approval. Managed tool confirmation requires its owner's platform identity or an explicitly delegated Application approver; interact scope alone does not grant approval authority. Native Managed action IDs start with `native:`. Their payload is `{"allow":true,"reason":"..."}` for confirmation or `{"output":"...","is_error":false}` for external execution.

Job inputs use the existing collaboration input mechanism; Agents refresh task context at safe boundaries. Managed Conversation input uses native steer. Command delivery does not mean the model consumed the input. External Conversation callers should submit the next turn after the current one ends.

Cancellation covers child executions and remains cancel_requested until physical attempts stop. Resume does not reopen an ended Invocation; submit new work when another execution is needed. Restoring a UI cursor is separate from native checkpoint recovery, which remains available through the Managed API.

## Conversations and releases

POST `/invoke/v1/endpoints/{slug}/conversations` with `{"message":"..."}`. Use the returned conversationId at POST `/invoke/v1/conversations/{conversationId}/turns` with a new submission key. A Conversation allows one active Invocation at a time.

Published releases capture schemas, targets and explicitly declared Team/Agent runtime configuration. Invocations and Conversations bind to that release. Later deployments or rollbacks do not rewrite existing requests. Outputs violating the published JSON Schema fail with output_schema_violation. Schemas support nested constraints and local $defs; remote $ref loading is disabled.

Health, permission and credential revocation checks remain live. Explicit nested Workflow references are recursively frozen with the release. Runtime-created child Workflows cannot bypass that release through an undeclared definition. Dynamic Agent/Team delegation follows the published delegation policy.

## Credentials, budgets and notifications

First create an Application with `POST /api/v1/applications` (`tenant`, `namespace`, `name`). Creating an Endpoint does not create a key. Call `POST /api/v1/endpoints/{endpointId}/credentials` with `applicationId`, `name` and an explicit nonempty `scopes` list. Scopes are invoke, read, cancel, interact and webhooks:write; there are no implicit default scopes.

Keys belonging to the same Application share invocation ownership across rotation and multiple credentials. Rotation creates a replacement credential and keeps the old credential active during migration. Move callers to the replacement, then explicitly revoke the old credential. Only the Application owner manages members and credentials. `members: [{userId, roles: ["viewer", "operator", "approver"]}]` grants the corresponding platform-user access; approval still requires the designated user. Application PATCH requires its `version`. Disabling an Application blocks new invocation and interaction; authorized human platform users may still read and cancel existing work. Keep long-lived keys in your backend proxy.

Application `maxConcurrent` limits active invocations across all its Endpoint keys. `tokenBudget` limits cumulative reported tokens, with read-only `tokensUsed`; zero means unlimited. These are application-wide controls, separate from per-Endpoint limits.

Endpoint rateLimit accepts requests/windowSeconds, maxConcurrent and maxInvocationTokens. Token enforcement cancels based on reported usage, so reporting latency can cause overshoot; it is not prepaid billing enforcement. Runtimes used with budgets must report usage.

POST `/webhooks` with `{"url":"https://your-app.example/events","event_types":["invocation.completed","required_action.created"]}`. Save signing_secret. Verify X-AgentScope-Signature (`t=<seconds>,v1=<hex>`) against `HMAC-SHA256(secret, t + '.' + raw body)` and check timestamp freshness. Deduplicate event IDs: retries provide at-least-once delivery.

GET `/webhooks` shows delivery state. Failures back off exponentially; after 12 consecutive failures, the subscription enters `failed` and automatic delivery stops. POST `/webhooks/{id}/retry` clears the failure count and retries the unacknowledged event; DELETE `/webhooks/{id}` disables delivery. Only public HTTPS destinations are supported, without redirects. Read the invocation for its final result after receiving a notification.

## Output mapping and capability discovery

Endpoint `resultMapping` maps public field names to RFC 6901 JSON Pointers into the complete raw execution output, for example `{"answer":"/report/text","sources":"/report/sources"}`. The service maps first, then validates the published outputSchema. Missing/invalid pointers fail with `output_mapping_failed`; schema violations fail with `output_schema_violation`. Omitting the mapping preserves the raw result. Mapping is frozen in each release.

Endpoint capabilities describe the guaranteed intersection of its published runtime candidates (`capability_basis: all_published_candidates`). After submission, read `GET /invoke/v1/invocations/{id}/capabilities`: it reflects selected bindings and returns `available_commands` filtered by current state. Drive cancel/input/resume buttons from it. Public checkpoint_restore remains false; use the native Managed API for checkpoints.

## Retention and expired cursors

`aistiod --service-event-retention` defaults to 720h; zero disables retention. Only terminal invocations older than the retention period lose historical deltas. Their complete materialized snapshot and source-event deduplication records remain available. An expired event cursor returns HTTP 410 with `error: cursor_expired` and `snapshot_url`. Fetch and replace the view with the new snapshot, then continue from its `as_of`; do not blindly retry the expired cursor.

## Runnable example and clients

`agentscope-service/aistio/examples/service-api` provides `bootstrap.py`, `worker.py` and `client.py`. Bootstrap creates an Application, registers two executable Agents, configures policies, creates a Team and Workflow, publishes all three Endpoint types and issues scoped credentials without Console. The default workers make no paid model calls. A real Managed member and designated approval can be enabled explicitly. See the example README for complete commands.

Python provides `aistio.ServiceClient` for invocation operations and `aistio.ManagementClient` for Applications, Agents, Teams, Workflow definitions, policies, Endpoints, releases and credentials. The TypeScript client and reducer are in `agentscope-service/frontend/src/api/serviceInvocations.ts`. OpenAPI and event schemas are under `agentscope-service/docs/service-api/`.

```ts
const initial = await api.snapshot(invocationId);
const view = new ServiceView(initial);
render(view.snapshot());
for await (const event of api.stream(invocationId, initial.as_of, signal)) {
  view.apply(event);
  render(view.snapshot());
}
```

Public events, snapshots, commands and webhook cursors live in the Service Store. PostgreSQL deployments survive process and replica changes. Native Agent session logs and checkpoints stay in Workspace/Filesystem storage, including distributed backends. These support native execution recovery independently of the public invocation history.

HTTP workers use `instrument(..., control_plane_http=base, transport="http")` by default. Outbound `/api/v1/agent-runtime/exchange` reuses fenced execution/report messages and durable command ACKs; no worker inbound port or externally reachable gRPC port is required. `transport="grpc"` remains available when ASDP is enabled. `AsyncInvokeAdapter` executes fresh `ainvoke` instances; `AgentScopeRunnerAdapter` executes fresh async AgentScope agents with native observation hooks. Both require an explicit task-to-framework input mapping. Observational adapters alone do not execute assigned tasks.

The current server must keep `--enable-asdp=true`: HTTP execution reuses its handlers. The local gRPC listener is initialized, but workers using HTTP do not need access to that port.

External SDK event records are limited to 16 MiB; HTTP uses batches targeting 16 MiB with a 32 MiB request limit, and gRPC allows 32 MiB messages. Oversized records fail explicitly without truncation. Publish large tool output as an artifact and include its reference in the event.
