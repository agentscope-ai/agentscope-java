---
title: "API, SDKs, and the Agent catalog"
zh_link: /v2/zh/service/api-reference
---

Applications follow Sessions, Turns and Events. Current MQ / Redis Managed Agents use the [native Managed contract](/v2/en/service/claude-session-api); management APIs prepare resources and definitions. Other backends retain the compatibility path described in the [API guide](/v2/en/service/service-api). Their payloads, event cursors and supported controls differ.

<span id="authentication-and-scope"></span>

## Prepare local API calls

Complete [local deployment](/v2/en/service/quickstart) and set `BASE_URL` and the default scope using [API setup](/v2/en/service/create-managed-agent#api-setup). Local mode needs no credentials or resource grants; management and Session API examples below work directly. See [production deployment](/v2/en/service/kubernetes#production-api-access) for accounts, grants, application keys, and authentication headers. Runtime-issued Task/Attempt credentials identify the current execution and still follow their protocol.

## Applications and credentials

An Application records the caller, members, and quotas. Credential creation requires explicit `targets` and `scopes` and returns the complete `apiKey` once. Listing credentials never returns plaintext keys. A credential does not automatically gain management permissions or a designated human's approval identity.

## Native Managed Session API

Current Managed execution uses `/v1/sessions`. Other backends retain the compatibility
`/api/v1/agent-sessions` path with different payloads and capabilities; do not mix their IDs or cursors.
The following paths are relative to the native base:

| API | Purpose |
| --- | --- |
| `POST /` | Create with `agent`, optional `environment_id`, `title` and `metadata`; retain a stable idempotency key |
| `GET /{session}` | Read frozen Agent reference and current Session status |
| `GET /{session}/capabilities` | Discover the supported protocol subset |
| `POST /{session}/events` | Submit `user.message` / `user.interrupt`; explicit `new_turn`, `steer`, `inject` and `cancel` require a stable key |
| `GET /{session}/events` | Paginate public history with `page`, `limit`, `order`, type/time filters |
| `GET /{session}/events/stream` | Receive events processed after connection; no cursor replay |
| `GET /{session}/turns` or `/turns/{turn}` | Read Turn identity and state using platform extensions |
| `GET /{session}/turns/{turn}/output` | Read independently paginated final output |
| `GET /{session}/turns/{turn}/inputs` or `/inputs/{input}` | Read Input admission and application state |

Select `AgentScope-Extensions: turn-inputs-v1` to include platform linkage in receipts and
history. Create Turns by posting Events, not by posting to `/turns`. Client tool confirmation,
Session list/update/lifecycle, snapshots, file/Artifact/budget/checkpoint APIs and SSE replay
are outside this first native protocol release. Internal checkpoints and automatic recovery
still operate; public feature availability is determined by capabilities. See the
[current native contract](/v2/en/service/claude-session-api) and
[first Managed Agent](/v2/en/service/create-managed-agent) for runnable requests.

<span id="agents"></span>
## Agent catalog and runtime bindings

These operations use platform identity. Keep tenant/namespace fields, query parameters, and headers consistent. An Agent ID, business key, and display name are distinct. See [Managed Agent](/v2/en/service/create-managed-agent), [Hosted Agent](/v2/en/service/connect-hosted-agent), and [External registration](/v2/en/service/register-agentscope-agent) for creation examples.

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

Use the local URL and default scope variables from [API setup](/v2/en/service/create-managed-agent#api-setup).

```bash
curl -sS --fail-with-body -G "$BASE_URL/api/v1/agents" \
  --data-urlencode "tenant=$TENANT" \
  --data-urlencode "namespace=$NAMESPACE" \
  --data-urlencode "status=active" \
  --data-urlencode "limit=25"
```

```bash
curl -sS --fail-with-body "$BASE_URL/api/v1/agents/$AGENT_ID/bindings"
```

```bash
curl -sS --fail-with-body -G "$BASE_URL/api/v1/agent-runtime-policies/$AGENT_ID" \
    --data-urlencode "tenant=$TENANT" \
    --data-urlencode "namespace=$NAMESPACE"
```

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

Python `ManagementClient` prepares resources and credentials; `ServiceClient` creates Sessions, submits Turns, and reads results. Application clients are also available as Java `io.agentscope.service.client.ServiceClient` (`agentscope-service/service-client`) and DSH package `ServiceClient`. The console TypeScript client is in `frontend/src/api/serviceSessions.ts`. Runtime registration and reporting SDKs connect executors and have a separate responsibility from application clients.

```python
import os
from agentscope_service import ServiceClient

client = ServiceClient(os.environ["BASE_URL"])
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

The current Portal deployment uses resource snapshot and receipt schemas above. Use the Service release manifest to select matching SDK versions; earlier Service schemas and command routes cannot be mixed with this deployment.

The Java client requires Java 17 and Jackson, without Service server dependencies. Calls block;
run them on application threads. Its default 30-second deadline includes response bodies and SSE
connections. Close streams and reconnect with the last applied cursor. Java, Python and DSH clients
bound JSON responses and uploads to 16 MiB and buffered downloads to 100 MiB; inspect Session
capabilities before file input. Build artifacts and SDK version metadata do not confirm Maven
repository publication.

## Managed runtime contract

All native interaction writes use `POST /v1/sessions/{session}/events`. Submit `user.message`
with `agentscope_service.intent:new_turn` for new work, or `steer` / `inject` with an existing
`turn_id`. Cancellation uses `user.interrupt` with `intent:cancel` and the explicit Turn ID.
Retain the same body and idempotency key after an uncertain response.

An extended receipt links Event and Turn/Input identities. Poll the corresponding read routes
until the Input is applied/rejected and the Turn reaches its actual terminal state. Acceptance
or applied cancellation does not prove that an external tool has stopped. SSE disconnects do
not cancel work; reconnect using public history and current-state queries, without a replay cursor.
There is no separate client `resume` operation. See the
[Managed Session API](/v2/en/service/claude-session-api) for the current capability boundary.
