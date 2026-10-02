---
title: "API reference: identity, resources and invocation"
zh_link: /v2/zh/service/api-reference
---

<Note>
This is preview documentation. The official release is not yet available.
</Note>

Use Gateway as the API base URL. Use [Agent API](/v2/en/service/session-event-log) for hosted sessions and inference control, and [Endpoints](/v2/en/service/endpoints) for published services with schemas, API keys and releases.

## Authentication and scope

| Identity | Purpose |
| --- | --- |
| User Bearer token | Agent API, product management, Chat, Issue and Workflow APIs |
| Endpoint API key | `X-API-Key` for its Endpoint invocations |
| Runtime Host credential | Host registration, heartbeat and execution claiming |
| Task / Attempt token | Injected, limited collaboration or execution reporting |
| Environment key | `X-Builder-Environment-Key` for the Worker protocol |
| Internal service token | Trusted component traffic, not ordinary user identity |

`POST /api/auth/login` accepts `{"username":"...","password":"..."}` and returns `token`. Send it as `Authorization: Bearer TOKEN`. Use `GET /api/auth/me` to check identity and `POST /api/auth/logout` to sign out.

Scope requests can carry `X-AgentScope-Tenant` and `X-AgentScope-Namespace`. Keep any tenant/namespace body or query fields consistent. The server determines authoritative scope in single-scope installations; use an authorized scope in multi-scope mode rather than assuming a shared default namespace.

## Common resources

Paths are relative to Gateway. `{id}` means a returned resource ID, not a display name.

| Operation | Method and path |
| --- | --- |
| Agent catalog and creation | `GET /api/v1/agents`, `POST /api/v1/agents` |
| Update definition | `PATCH /api/v1/agents/{id}/definition` |
| Conversation-capable Agents | `GET /api/v1/chat-agents` |
| List and create Chats | `GET /api/v1/chats`, `POST /api/v1/chats` |
| Send a turn | `POST /api/v1/chats/{id}/turns` |
| List and create Issues | `GET /api/v1/issues`, `POST /api/v1/issues` |
| Issue summary and comments | `GET /api/v1/issues/{id}/summary`, `GET/POST /api/v1/issues/{id}/comments` |
| Accept or return work | `POST /api/v1/issues/{id}/accept`, `POST /api/v1/issues/{id}/reject` |
| Inbox and approval | `GET /api/v1/inbox`, `POST /api/v1/approvals/{id}/decide` |
| Teams | `GET/POST /api/v1/teams` |
| Workflows | `GET/POST /api/v1/orchestration-definitions` |
| Publish a Workflow | `POST /api/v1/orchestration-definitions/{id}/publish` |
| Execution graph and events | `GET /api/v1/orchestration-runs/{id}/graph`, `GET /api/v1/orchestration-runs/{id}/events` |
| Automations | `GET/POST /api/v1/automations` |
| Endpoint management | `GET/POST /api/v1/endpoints` |

## Managed Agent inference API

Use a platform user Bearer token as the session owner; no Endpoint publication is needed. Endpoint API keys do not apply. Begin with the [chat example](/v2/en/service/agent-api-chat), then consult the [operation guide](/v2/en/service/session-event-log) and [SSE events](/v2/en/service/sse-events).

In the tables below, `{S}` means `/api/v1/agent-sessions/{session}`, `{T}` means `{S}/turns/{turn}`, and `{C}` means `{S}/subagents/{child}`. Expand them into complete paths in actual requests.

### Session lifecycle

| Operation | Method and path |
| --- | --- |
| Create / list sessions | `POST /api/v1/agent-sessions`<br />`GET /api/v1/agent-sessions` |
| Get / update / delete | `GET {S}`<br />`PATCH {S}`<br />`DELETE {S}` |
| Archive / unarchive | `POST {S}/archive`<br />`POST {S}/restore` |

### Task submission and interaction

| Operation | Method and path |
| --- | --- |
| Submit / list / get turns | `POST {S}/turns`<br />`GET {S}/turns`<br />`GET {T}` |
| Steer / inject context | `POST {T}/steer`<br />`POST {S}/inputs/inject` |
| Answer actions / query answer commands | `POST {T}/actions`<br />`GET {T}/actions` |
| Cancel / resume a task | `POST {T}/cancel`<br />`POST {T}/resume` |

### Rendering, subscriptions and tracing

| Operation | Method and path |
| --- | --- |
| Snapshot / SSE | `GET {S}/snapshot`<br />`GET {S}/events/stream` |
| Event pages / single event / export | `GET {S}/events`<br />`GET {S}/events/{event}`<br />`GET {S}/export` |
| Messages / tools / actions / inputs | `GET {S}/items`<br />`GET {S}/tools`<br />`GET {S}/required-actions`<br />`GET {S}/inputs` |
| Resource pages at a fixed snapshot | `GET {S}/resources/{resource}` |
| Child list / details / snapshot | `GET {S}/subagents`<br />`GET {C}`<br />`GET {C}/snapshot` |
| Child history / SSE | `GET {C}/events`<br />`GET {C}/events/stream` |
| Child resources | `GET {C}/items`<br />`GET {C}/tools`<br />`GET {C}/turns`<br />`GET {C}/runs`<br />`GET {C}/required-actions`<br />`GET {C}/usage`<br />`GET {C}/subagents` |

### Files, recovery and operations

| Operation | Method and path |
| --- | --- |
| Upload / list / file metadata / download | `POST {S}/files`<br />`GET {S}/files`<br />`GET {S}/files/{file}`<br />`GET {S}/files/{file}/content` |
| Publish / list / revoke artifacts | `POST {S}/artifacts`<br />`GET {S}/artifacts`<br />`DELETE {S}/artifacts/{artifact}` |
| List checkpoints / restore context / fork | `GET {S}/checkpoints`<br />`POST {S}/checkpoints/restore`<br />`POST {S}/fork` |
| Usage / budget | `GET {S}/usage`<br />`GET {S}/budget`<br />`PUT {S}/budget` |
| Register / list / delete webhooks | `POST {S}/webhooks`<br />`GET {S}/webhooks`<br />`DELETE {S}/webhooks/{webhook}` |
| Webhook retry / deliveries | `POST {S}/webhooks/{webhook}/retry`<br />`GET {S}/webhooks/{webhook}/deliveries` |

Use Idempotency-Key for turns, actions, steer, inject, file upload, artifact publication, checkpoint restore/fork and webhook registration. Retries keep the original key and payload; new submissions use new keys. A 202 means durable acceptance only. Creation/task status responses use camelCase fields such as sessionId; public event envelopes use snake_case.

SSE and event history use opaque session cursors. Resource-page cursors, checkpoint_id and other sessions' cursors are not interchangeable. `POST {S}/restore` unarchives, `POST {S}/checkpoints/restore` restores Agent context, and `POST {T}/resume` continues the original task.

Administrator diagnostics use `GET {S}/trace`, `GET {S}/trace/recovery`, `GET {S}/trace/subagents/{child}` and `POST {S}/trace/reconcile`. These require trace enabled by the operator, session ownership and an appropriate administrator role. Chat UIs do not need them; see [diagnostics and tool reconciliation](/v2/en/service/session-event-log#administrator-checkpoint-and-tool-reconciliation).

The complete machine contract is `agentscope-service/docs/agent-api/openapi-v1.json`; `public-event-v1.schema.json` in the same directory defines events.

## Chat request

Create a Chat with an existing Agent ID and authorized scope. Use the returned `chat.id` for turns:

```json
{
  "tenant": "YOUR_TENANT",
  "namespace": "YOUR_NAMESPACE",
  "agentId": "AGENT_ID",
  "title": "Notes review"
}
```

A turn body is `{"message":"Summarize this material"}`. This product API differs from the runtime `/api/sessions` event protocol; do not mix their payloads.

## Issue review and concurrent edits

Read `GET /api/v1/issues/{id}` and inspect the result and `issue.version`. Accept with `{"expectedVersion":7}`, or reject with `{"expectedVersion":7,"reason":"Missing source evidence"}`. Replace 7 with the reviewed version.

Version fields differ: Chat patch uses `version`, Issue decisions and Workflow publication use `expectedVersion`, and Endpoint publication uses `version`. After 409, reread and review rather than automatically applying an old decision to a new version.

## Endpoint invocation

See the [Endpoint guide](/v2/en/service/endpoints) for jobs, conversations, credentials, states and SSE. Submission uses a logical-request Idempotency-Key. Follow returned statusUrl/eventsUrl instead of bypassing the Endpoint to manipulate internal tasks.

## Errors

| Response | Action |
| --- | --- |
| 400 / validation failure | Check JSON, required fields, schemas and capabilities |
| 401 | Check credential type, expiry and identity |
| 403 | Check scope role and work permissions |
| 404 | Check ID, scope and resource state; do not infer another user's resource existence |
| 409 | Reread versions or check changed idempotent payloads |
| 429 | Back off as directed while preserving the logical request key |
| 5xx / network timeout | Query already-submitted work before retrying |

Record correlation/resource IDs, time, status code and a redacted error. See [External Agents](/v2/en/service/external-agent) for SDK adapters and [execution reference](/v2/en/service/sessions) for states.

## SDK execution cancellation

The following belongs to the existing runtime Session control protocol. Agent API applications stop a target task through `POST /api/v1/agent-sessions/{session}/turns/{turn}/cancel`.

For the data-plane session event API, `session.run_started` includes a `run_id` identifying one SDK invocation. This ID is distinct from an orchestration Run or managed Attempt ID. Use the same authorized session to request precise cancellation:

```http
POST /api/sessions/{id}/events
Authorization: Bearer TOKEN
Content-Type: application/json

{"events":[{"type":"user.interrupt","payload":{"run_id":"<id from session.run_started>"}}]}
```

The service checks session access and local run ownership. A remote request is fenced by this run ID; if the run has ended or been replaced, it never cancels a newer run. Omitting `run_id` retains the explicit session-wide “interrupt current turn” operation. Local run registrations are removed on completion, failure and cancellation; diagnostic history remains in session events.
