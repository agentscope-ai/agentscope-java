---
title: "API, SDKs, and the Agent catalog"
zh_link: /v2/zh/service/api-reference
---

Applications use the Session API for Agents, Teams, and Workflows. Management APIs prepare resources, permissions, and published Workflow revisions. Callers select a target when creating a Session; there is no separate service publication step. Follow the [API guide](/v2/en/service/service-api) for the complete flow and use this page as a path and parameter reference.

## Authentication and scope

Platform users log in with `POST /api/auth/login` and `{"username":"...","password":"..."}`, then send the returned `token` as `Authorization: Bearer TOKEN`. This identity manages Agents, Teams, Workflows, and Applications. Application backends instead use `X-API-Key` credentials issued under an Application, restricted to explicit target grants and scopes.

Set `X-AgentScope-Tenant` and `X-AgentScope-Namespace` for scoped requests. Body and query scope fields, when supplied, must agree. Runtime Host, Task/Attempt, and Environment credentials belong to their execution protocols and cannot substitute for Session API credentials.

## Applications and credentials

An Application records the caller, members, and quotas. Credential creation requires explicit `targets` and `scopes` and returns the complete `apiKey` once. Listing credentials never returns plaintext keys. A credential does not automatically gain management permissions or a designated human's approval identity.

| API | Parameters and behavior |
| --- | --- |
| `POST /api/v1/applications` | `name`, `tenant`, `namespace`; `description`, `members`, `maxConcurrent`, `tokenBudget` |
| `GET /api/v1/applications` / `/{id}` | List or detail |
| `PATCH /api/v1/applications/{id}` | `version`, `name`, `description`, `status`, `members`, `maxConcurrent`, `tokenBudget` |
| `POST /api/v1/applications/{id}/credentials` | `name`, `targets:[{type,id}]`, `scopes`; `expiresAt` |
| `GET /api/v1/applications/{id}/credentials` | Credential metadata |
| `DELETE /api/v1/applications/{id}/credentials/{credentialId}` | Revoke the credential |

Credential scopes are `invoke`, `read`, `interact`, `cancel`, and `webhooks:write`. Application member roles are `viewer`, `operator`, and `approver`. Concurrency limits bound outstanding work. `tokenBudget` uses reported usage to govern further calls; it is not a provider prepaid hard limit.

## Session and Turn API

Paths below use `/api/v1/agent-sessions` as their prefix. Set `target.type` to `agent`, `team`, or `workflow`, and `target.id` to that resource's ID. Agents optionally accept `version`; Workflows accept `revisionId`. When omitted, the version is resolved and frozen at Session creation for subsequent Turns.

Managed Sessions also accept `environmentId`, `memoryStoreIds`, `vaultIds`, `agentOverrides`, and `resources`. General options include `timeoutSeconds`, `budget.maxTokens`, `inputSchema`, `outputSchema`, and `resultMapping`. Supply business input and output constraints when creating the Session.

| API | Purpose |
| --- | --- |
| `POST /` | Create a Session; supports an idempotency key |
| `GET /` | List: agentId, status, limit, offset |
| `GET /{session}` | Read Session and frozen target |
| `PATCH /{session}` | Update Managed environment and resource settings |
| `POST /{session}/archive, /restore` | Archive or unarchive |
| `DELETE /{session}` | Remove application access to the Session |
| `GET /{session}/capabilities` | Target capabilities and context semantics |
| `POST /{session}/turns` | message or input; idempotency key required |
| `GET /{session}/turns, /turns/{turn}` | Turn list and status |
| `GET /{session}/snapshot, /events, /events/stream` | Session snapshot and durable events |
| `GET /{session}/turns/{turn}/snapshot, /events, /events/stream` | One Turn’s snapshot and events |
| `GET /{session}/turns/{turn}/capabilities` | Read available_commands |
| `POST /{session}/turns/{turn}/actions, /inputs, /cancel, /resume` | Interaction commands; idempotency key required |
| `GET /{session}/turns/{turn}/commands/{command}` | Command receipt status |
| `POST/GET /{session}/files` | Upload bytes or list files |
| `GET /{session}/files/{file}/content` | Download an authorized file |
| `POST/GET /{session}/webhooks` | Register or inspect notification subscriptions |
Paths after commas share the prefix of the first path in the row. Turns also expose `/actions`, `/usage`, `/artifacts`, and `/webhooks`. Revoke a Webhook with `DELETE /webhooks/{id}` and retry with `POST /webhooks/{id}/retry`.

Managed `/budget`, `/checkpoints`, `/fork`, `/inputs/inject`, `/subagents`, and `/export` extend the same API. They are not supported by every target; follow the [Session guide](/v2/en/service/session-event-log) and capabilities. See [Files](/v2/en/service/files) and [SSE](/v2/en/service/sse-events) for formats and signatures.

<span id="agents"></span>
## Agent catalog and runtime bindings

These operations use platform identity. Keep tenant/namespace fields, query parameters, and headers consistent. An Agent ID, business key, and display name are distinct. See [Agent management](/v2/en/service/api-reference#agents) for creation and registration workflows.

| Method and route | Request parameters | Response and purpose |
| --- | --- | --- |
| `POST /api/v1/agents` | `agentKey`; scope, `displayName`, `description`; optional `binding`, `definition` | `{agent,binding,policy,definition}` for Managed/Hosted provisioning; omitting binding creates only a catalog record |
| `GET /api/v1/agents` | Query `tenant`, `namespace`, `status`, `includeArchived`, `limit` | `{items:[Agent]}` |
| `GET/PATCH /api/v1/agents/{id}` | PATCH: `version` and changed `displayName`, `description`, `status`, etc. | `{agent}`; archive through `status:"archived"` without deleting history |
| `GET/PATCH /api/v1/agents/{id}/definition` | PATCH: `name`, definition `version`, and behavior fields with unchanged values preserved | `{definition}`; updates also return `agent`; [Definition fields](/v2/en/service/managed-agent-configuration) |
| `GET /api/v1/agents/{id}/versions` / `/{version}` | Agent ID and optional definition version | Definition history; choose a version when creating a Session |
| `GET/POST /api/v1/agents/{id}/bindings` | Create with `kind`, `configuration`, `priority`, `enabled` | Read bindings from the list; update through `PATCH .../bindings/{bindingId}` with `version` and complete `configuration`, `priority`, `enabled` values |
| `GET /api/v1/agents/{id}/instances` / `/runtime-inventory` / `/overview` | Agent ID | Instances, External runtime reports, and overview; missing reports do not establish readiness |
| `GET/PUT /api/v1/agent-runtime-policies/{id}` | PUT: scope, `agentId`, `selectionMode`, `fallbackMode`, `candidates`, etc. | Runtime selection; [Policy reference](/v2/en/service/team-configuration) |
| `GET /api/v1/agents/runtime-options` | Query `tenant`, `namespace` | `{runtimes,profiles,pools}` for Hosted selection |

`binding.kind` is `managed`, `hosted-runtime`, or `external-application`. Managed creation generates its configuration. Hosted uses `runtimeProfileId` and `runtimePoolId`. External applications use registration rather than creating a supposedly online instance through the catalog endpoint.

`POST /api/v1/agent-registrations` accepts `agentKey`, `instanceKey`, scope, `framework`, `routingKey`, `capabilities`, and related fields. It returns `agent`, `binding`, `instance`, and `registrationCredential`. This entry point currently does not authenticate callers; deployment must restrict it to trusted registration traffic. Returning a credential does not mean initial registration was authenticated. See [External configuration](/v2/en/service/external-agent#external-agent-configuration).

## Work, orchestration, automation, and resource parameters

These guides document request fields, responses, and operation sequences. Visual workflows live in the separate [Console module](/v2/en/service/console/index).

| Resource | API entry point | Parameter reference |
| --- | --- | --- |
| Issue / AgentTask / Inbox / Approval | `/api/v1/issues`, `/agent-tasks`, `/inbox`, `/approvals` | [Assignment](/v2/en/service/issues), [Feedback](/v2/en/service/issues#inbox), [Execution records](/v2/en/service/sessions) |
| Team and members | `/api/v1/teams` | [Team configuration](/v2/en/service/team-configuration), [Collaboration](/v2/en/service/create-team#team-collaboration) |
| Workflow definitions / revisions / runs | `/api/v1/orchestration-definitions`, `/orchestration-runs` | [Nodes, publishing, runs and signals](/v2/en/service/workflows) |
| Automation and deliveries | `/api/v1/automations` | [Trigger, action and delivery fields](/v2/en/service/automation) |
| Channels | `/api/channels` | [Configuration and work routing](/v2/en/service/channels) |
| Workspace | `/api/workspaces` | [Files, versions and bindings](/v2/en/service/workspaces) |
| Environment | `/api/environments` | [Type, configuration and Workers](/v2/en/service/environments) |
| Memory | `/api/memory-stores` | [Documents, versions and access](/v2/en/service/memory) |
| Vault | `/api/vaults` | [Secrets, scope and references](/v2/en/service/vault) |
| Namespace and permissions | Resource-specific authorization routes | [Access reference](/v2/en/service/access) |



<span id="integrations"></span>

## SDKs and runnable examples

Python `ManagementClient` prepares resources and credentials; `ServiceClient` creates Sessions, submits Turns, and reads results. The TypeScript client is in `frontend/src/api/serviceSessions.ts`. Runtime registration and reporting SDKs connect executors and have a separate responsibility from application clients.

```python
import os
from agentscope_service import ServiceClient

client = ServiceClient(os.environ["BASE_URL"], api_key=os.environ["AGENTSCOPE_API_KEY"])
session = client.create_session(
    {"type": "agent", "id": os.environ["AGENT_ID"]},
    idempotency_key="review-session-001",
)
turn = client.submit(session["id"], message="Review the notes", idempotency_key="review-001")
print(client.turn(session["id"], turn["id"]))
print(client.snapshot(session["id"]))
```

The complete example in `service-controlplane/examples/service-api` creates Sessions for an Agent, Team, and Workflow. Clients do not approve tools or create a new task automatically after a network error. Persist Session IDs, Turn IDs, and idempotency keys and follow the [API guide](/v2/en/service/service-api) for recovery.

## Errors and version conflicts

For `400`, check the request. `401` indicates invalid credentials and `403` insufficient permission. `404` can mean absent or inaccessible data and should not reveal another user's resources. With `409`, check state, versions, or changed idempotent requests. Event `410` requires a fresh snapshot. Back off on throttling and transient errors while preserving the logical request's idempotency key.

Read the current version before updating management resources. Issue decisions and Workflow publication use `expectedVersion`; Agent definition updates use their own `version` fields. Do not automatically replay an outdated human decision with a newer version.

The machine-readable contract is `agentscope-service/docs/service-api/openapi-v1.json`. It lists Session API and application credential paths. Migration from the previous entry points is described in the adjacent `session-api-migration.md`.
