---
title: "API reference: identity, resources and invocation"
zh_link: /v2/zh/service/api-reference
---

<Note>
This is preview documentation. The official release is not yet available.
</Note>

Use Gateway as the API base URL. Agent APIs cover management, publishing, invocation, feedback, and orchestration. Endpoint/Invocation is the unified application interface; native Managed sessions are a runtime extension. Start with the [API quickstart](/v2/en/service/first-session); this page collects routes, parameters, and authentication boundaries.

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

## Agent catalog and runtime bindings

These operations use platform identity. Keep tenant/namespace fields, query parameters, and headers consistent. An Agent ID, business key, and display name are distinct. See [Agent management](/v2/en/service/agents) for creation and registration workflows.

| Method and route | Request parameters | Response and purpose |
| --- | --- | --- |
| `POST /api/v1/agents` | `agentKey`; scope, `displayName`, `description`; optional `binding`, `definition` | `{agent,binding,policy,definition}` for Managed/Hosted provisioning; omitting binding creates only a catalog record |
| `GET /api/v1/agents` | Query `tenant`, `namespace`, `status`, `includeArchived`, `limit` | `{items:[Agent]}` |
| `GET/PATCH /api/v1/agents/{id}` | PATCH: `version` and changed `displayName`, `description`, `status`, etc. | `{agent}`; archive through `status:"archived"` without deleting history |
| `GET/PATCH /api/v1/agents/{id}/definition` | PATCH: `name`, definition `version`, and behavior fields with unchanged values preserved | `{definition}`; updates also return `agent`; [Definition fields](/v2/en/service/managed-agent-configuration) |
| `GET /api/v1/agents/{id}/versions` / `/{version}` | Agent ID and optional definition version | Definition history, distinct from Endpoint releases |
| `GET/POST /api/v1/agents/{id}/bindings` | Create with `kind`, `configuration`, `priority`, `enabled` | Read bindings from the list; update through `PATCH .../bindings/{bindingId}` with `version` and complete `configuration`, `priority`, `enabled` values |
| `GET /api/v1/agents/{id}/instances` / `/runtime-inventory` / `/overview` | Agent ID | Instances, External runtime reports, and overview; missing reports do not establish readiness |
| `GET/PUT /api/v1/agent-runtime-policies/{id}` | PUT: scope, `agentId`, `selectionMode`, `fallbackMode`, `candidates`, etc. | Runtime selection; [Policy reference](/v2/en/service/team-configuration) |
| `GET /api/v1/agents/runtime-options` | Query `tenant`, `namespace` | `{runtimes,profiles,pools}` for Hosted selection |

`binding.kind` is `managed`, `hosted-runtime`, or `external-application`. Managed creation generates its configuration. Hosted uses `runtimeProfileId` and `runtimePoolId`. External applications use registration rather than creating a supposedly online instance through the catalog endpoint.

`POST /api/v1/agent-registrations` accepts `agentKey`, `instanceKey`, scope, `framework`, `routingKey`, `capabilities`, and related fields. It returns `agent`, `binding`, `instance`, and `registrationCredential`. This entry point currently does not authenticate callers; deployment must restrict it to trusted registration traffic. Returning a credential does not mean initial registration was authenticated. See [External configuration](/v2/en/service/external-agent-configuration).

## Application, Endpoint, and release parameters

Management uses platform identity. Application owners manage their applications; invocation access combines Endpoint policy, Application ownership, and credential scopes.

| Method and route | Key parameters | Response or behavior |
| --- | --- | --- |
| `POST /api/v1/applications` | Scope, `name`; optional `description`, `members`, `maxConcurrent`, `tokenBudget` | `{application}`; retain its ID |
| `GET /api/v1/applications` / `/{id}` | Scope for listing | List or detail |
| `PATCH /api/v1/applications/{id}` | `version`; editable name, description, status, members, concurrency and token budget | Members use `userId` and `roles`: `viewer`, `operator`, `approver` |
| `POST /api/v1/endpoints` | Scope, `name`, `slug`, `targetType`, `targetRef`, `invocationMode`, `authPolicy` | Draft `{endpoint}`; no automatic key |
| `GET /api/v1/endpoints` / `/{id}` / `/{id}/readiness` | Scope for listing, Endpoint ID for detail | Definition or readiness |
| `PATCH /api/v1/endpoints/{id}` | `version`; name, description, schemas, `resultMapping`, `rateLimit`, `timeoutSeconds`, `maxPayloadBytes` | Published schemas/resultMapping cannot change through ordinary PATCH |
| `POST /api/v1/endpoints/{id}/publish` / `/disable` | `version` | Publish or stop new submissions; disabling does not cancel work |
| `GET/POST /api/v1/endpoints/{id}/releases` | POST: `version`, `targetRef`, optional `reason` | Immutable target and contract release |
| `POST /api/v1/endpoints/{id}/releases/{releaseId}/rollback` | `version` | Select a previous release; no external-effect rollback |
| `POST /api/v1/endpoints/{id}/credentials` | `applicationId`, `name`, nonempty `scopes`, optional `expiresAt` | `{credential,secret}`; store the key in the caller's backend |
| `GET /api/v1/endpoints/{id}/credentials` | Endpoint ID | Credential list |
| `POST /api/v1/endpoints/{id}/credentials/{credentialId}/rotate` | Credential ID | Replacement credential and secret; old key remains valid until revoked |
| `DELETE /api/v1/endpoints/{id}/credentials/{credentialId}` | Credential ID | Revoke |
| `DELETE /api/v1/endpoints/{id}` | `version` | Archive Endpoint |

`targetType` is `agent`, `team`, or `orchestration_revision`; the last requires a published revision ID. `invocationMode` is `job` or `conversation`; Teams/Workflows currently require job. `authPolicy.type` is `api_key` or `platform`.

`inputSchema` and `outputSchema` validate input/output. `resultMapping` maps output field names to JSON Pointers into the complete result. `rateLimit` accepts `requests`, `windowSeconds`, `maxConcurrent`, and `maxInvocationTokens`. Application concurrency spans Endpoints and keys. Its `tokenBudget` is cumulative reported usage, with zero unlimited, rather than a prepaid hard limit.

Credential scopes are `invoke`, `read`, `cancel`, `interact`, and `webhooks:write`. `interact` alone does not grant a designated approver's authority. See [Endpoint publishing](/v2/en/service/endpoints).

<span id="endpoint-invocation"></span>

## Unified Invocation API parameters

These resources serve Agent, Team, and Workflow invocations through Endpoints. Authentication follows Endpoint policy; management, invocation, and execution credentials are distinct.

| Operation | Method and route | Request / response highlights |
| --- | --- | --- |
| Published capabilities | `GET /invoke/v1/endpoints/{slug}/capabilities` | Capabilities guaranteed across published candidates |
| Submit work | `POST /invoke/v1/endpoints/{slug}/jobs` | `title`, optional `description`, schema-compatible `input`; Idempotency-Key required |
| Start a conversation | `POST /invoke/v1/endpoints/{slug}/conversations` | `message`, idempotency key; returns conversationId and first invocationId |
| Next turn | `POST /invoke/v1/conversations/{id}/turns` | `message`, new key for new work; one active Invocation at a time |
| State and capabilities | `GET /invoke/v1/invocations/{id}` / `/capabilities` | `invocation.status`, `result`; state-filtered `available_commands` |
| Snapshot | `GET /invoke/v1/invocations/{id}/snapshot` | `as_of`, the `invocation` state object, and ID-indexed `items/tools/required_actions/steps/artifacts/usage` |
| Event pages / SSE | `GET .../{id}/events` / `/events/stream` | `after`, history `limit`; Last-Event-ID takes precedence for SSE |
| Pending actions / answers | `GET/POST .../{id}/actions` | POST: `request_id`, required `expected_version`, action-specific `decision` or `payload` |
| Additional input | `POST .../{id}/inputs` | `message`; requires target capability |
| Cancel / resume | `POST .../{id}/cancel` / `/resume` | `{}`; subject to capabilities and current state |
| Command receipt | `GET .../{id}/commands/{commandId}` | Accepted, completed, or failed command |
| Usage / artifacts | `GET .../{id}/usage` / `/artifacts` | Reported usage and download references |
| Register / list Webhooks | `POST/GET .../{id}/webhooks` | POST: `url`, `event_types`; signing secret returned; webhooks:write scope |
| Retry / disable Webhook | `POST .../{id}/webhooks/{webhookId}/retry`; `DELETE .../{id}/webhooks/{webhookId}` | Retry unacknowledged delivery or disable subscription |

`.../{id}` means `/invoke/v1/invocations/{id}`. Submission and interaction commands require logical-request Idempotency-Key values. `202` or a command receipt means durable acceptance, not that a model consumed the input or the operation completed.

Capabilities differ: Jobs support work-context input, approval, and cancellation; Workflow Jobs may resume when their state permits. Managed Conversations expose native interactions; Hosted Conversations currently support cancellation, and External cancellation depends on `session-abort`. Public checkpoint_restore remains false. See the [Unified service API](/v2/en/service/service-api) for full action bodies and frontend behavior.

## Work, orchestration, automation, and resource parameters

These guides document request fields, responses, and operation sequences. Visual workflows live in the separate [Console module](/v2/en/service/console/index).

| Resource | API entry point | Parameter reference |
| --- | --- | --- |
| Issue / AgentTask / Inbox / Approval | `/api/v1/issues`, `/agent-tasks`, `/inbox`, `/approvals` | [Assignment](/v2/en/service/issues), [Feedback](/v2/en/service/inbox), [Execution records](/v2/en/service/sessions) |
| Team and members | `/api/v1/teams` | [Team configuration](/v2/en/service/team-configuration), [Collaboration](/v2/en/service/team-collaboration) |
| Workflow definitions / revisions / runs | `/api/v1/orchestration-definitions`, `/orchestration-runs` | [Nodes, publishing, runs and signals](/v2/en/service/workflows) |
| Automation and deliveries | `/api/v1/automations` | [Trigger, action and delivery fields](/v2/en/service/automation) |
| Channels | `/api/channels` | [Configuration and work routing](/v2/en/service/channels) |
| Workspace | `/api/workspaces` | [Files, versions and bindings](/v2/en/service/workspaces) |
| Environment | `/api/environments` | [Type, configuration and Workers](/v2/en/service/environments) |
| Memory | `/api/memory-stores` | [Documents, versions and access](/v2/en/service/memory) |
| Vault | `/api/vaults` | [Secrets, scope and references](/v2/en/service/vault) |
| Namespace and permissions | Resource-specific authorization routes | [Access reference](/v2/en/service/access) |

Not all resource paths use `/api/v1/`; do not rewrite `/api/...` routes automatically. `/api/v1/events` is a best-effort UI-refresh WebSocket without durable replay cursors. Use Invocation SSE for reliable service tracking and native session SSE for Managed session tracking.

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

The following belongs to the existing runtime Session control protocol. Native Managed session clients stop a target task through `POST /api/v1/agent-sessions/{session}/turns/{turn}/cancel`.

For the data-plane session event API, `session.run_started` includes a `run_id` identifying one SDK invocation. This ID is distinct from an orchestration Run or managed Attempt ID. Use the same authorized session to request precise cancellation:

```http
POST /api/sessions/{id}/events
Authorization: Bearer TOKEN
Content-Type: application/json

{"events":[{"type":"user.interrupt","payload":{"run_id":"<id from session.run_started>"}}]}
```

The service checks session access and local run ownership. A remote request is fenced by this run ID; if the run has ended or been replaced, it never cancels a newer run. Omitting `run_id` retains the explicit session-wide “interrupt current turn” operation. Local run registrations are removed on completion, failure and cancellation; diagnostic history remains in session events.
