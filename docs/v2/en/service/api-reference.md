---
title: "API reference: identity, resources and invocation"
---

[简体中文](/v2/zh/service/api-reference)

Use Gateway as the API base URL. Product management uses user identity; applications should generally invoke published capabilities through [Endpoints](/v2/en/service/endpoints).

## Authentication and scope

| Identity | Purpose |
| --- | --- |
| User Bearer token | Product management, Chat, Issue and Workflow APIs |
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
