---
title: "API, SDKs, and the Agent catalog"
zh_link: /v2/zh/service/api-reference
---

Applications use published Endpoint / Invocation APIs for business integration, or the native Managed Session API for direct Session and Turn control. Management APIs prepare resources and definitions. Follow the [API guide](/v2/en/service/service-api) for public business calls and the [native Managed contract](/v2/en/service/claude-session-api) for the MQ / Redis execution path.

<span id="authentication-and-scope"></span>

## Prepare local API calls

Complete [local deployment](/v2/en/service/quickstart) and set `BASE_URL` and the default scope using [API setup](/v2/en/service/create-managed-agent#api-setup). Local mode needs no credentials or resource grants; management and Session API examples below work directly. See [production deployment](/v2/en/service/kubernetes#production-api-access) for accounts, grants, application keys, and authentication headers. Runtime-issued Task/Attempt credentials identify the current execution and still follow their protocol.

## Applications and credentials

An Application records the caller, members, and quotas. Credential creation requires explicit `targets` and `scopes` and returns the complete `apiKey` once. Listing credentials never returns plaintext keys. A credential does not automatically gain management permissions or a designated human's approval identity.

## Native Managed Session API

Managed execution uses `/v1/sessions`. The following paths are relative to that base. Other
backends retain `/api/v1/agent-sessions` with their own payloads, capabilities and cursors;
do not send native Managed IDs or Input bodies to those routes.

| API | Purpose |
| --- | --- |
| `POST /` | Pin `agent`, optional `environment_id`, resources and configuration; idempotency key required |
| `GET /` | Keyset list: `after`, `limit`, `order`, `lifecycle`, `agent_id` |
| `GET /{session}` | Read the Session and frozen configuration |
| `POST /{session}` | Update `title` and string-pair `metadata` |
| `POST /{session}/archive` or `/restore`; `DELETE /{session}` | Accept a lifecycle change with a stable key |
| `GET /{session}/lifecycle/{command}` | Read that lifecycle operation's durable receipt |
| `GET /{session}/capabilities` | Explicit file, artifact, budget and checkpoint capabilities |
| `POST /{session}/turns` | Submit `{"input":{"message":"Task"}}` or structured Agent input; stable key required |
| `GET /{session}/turns` | `after`, `limit`, `order`, `status` |
| `GET /{session}/turns/{turn}` | Admission, execution, delivery and `inputs_open` |
| `POST /{session}/turns/{turn}/inputs` | `type=steer|inject|reply|cancel`, `payload`, and `action_id` for reply |
| `GET /{session}/turns/{turn}/inputs` or `/inputs/{input}` | Ordered Input list or one admission/application receipt |
| `GET /{session}/snapshot` or `/turns/{turn}/snapshot` | Pinned, bounded resources for the Session or one Turn |
| `GET /{session}/resources/{kind}` | Continue either snapshot's pages with `snapshot_id`, `after`, `limit` |
| `GET /{session}/events` or `/events/stream` | Session journal pagination or persistent SSE |
| `GET /{session}/usage` or `/turns/{turn}/usage` | Authoritative aggregate and committed cursor |
| `GET /{session}/turns/{turn}/capabilities` | Available controls and explicit support flags |
| `POST/GET /{session}/files` | Immutable binary upload or finite metadata list |
| `GET /{session}/files/{file}` or `/files/{file}/content` | File metadata or bytes |
| `POST/GET /{session}/artifacts` | Publish a deliverable or read a pinned resource page |
| `GET/DELETE /{session}/artifacts/{artifact}` | Read or remove a publication; removal retains file bytes |
| `GET /{session}/artifacts/{artifact}/content` | Platform-owned content; HTTPS references are not fetched |
| `GET/PUT /{session}/budget` | Read or replace the shared root budget; PUT uses `If-Match` |
| `GET /{session}/checkpoints` | Committed checkpoint metadata, with its own pagination watermark |

Paths after “or” share the first path's Session prefix. Native Turn observations use a Turn
snapshot plus the **Session** event stream; filter events by `data.turn_id` while advancing the
Session cursor for every applied frame. There is no native Turn-specific events route.
Snapshots return bounded `resources.<kind>.data` pages with `snapshot_id`, `as_of`, expiry and
continuation fields. The Console helper flattens these pages; that view type is not the raw response.

Replies and cancellation are Inputs with separate delivery and application status. Long-tool
suspension resumes automatically. Current capabilities report `resume=false`,
`checkpoint_restore=false` and `fork=false`; the old `/actions`, `/commands`, tool/workspace
recovery and trace routes are not native controls. Native webhook registration and complete
historical-state recovery remain migration work. See the [native contract](/v2/en/service/claude-session-api),
[Files](/v2/en/service/files) and [SSE](/v2/en/service/sse-events).

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

Managed Sessions use `/v1/sessions`. Pending actions are in
`snapshot.resources.required_actions.data`. Reply through
`POST /v1/sessions/{session}/turns/{turn}/inputs` with
`{"type":"reply","action_id":"ACTION_ID","payload":{"allow":true,"reason":"..."}}`
for a confirmation. Keep the same explicit idempotency key and body when retrying.

The Input receipt contains `id`, `turn_id`, `status` and `delivery_status`.
`status` is `accepted`, `applied` or `rejected`; a rejected Input includes
`rejection_reason`. Read the receipt at
`GET /v1/sessions/{session}/turns/{turn}/inputs/{input}`. Delivery does not imply
application. A cancel Input being applied does not prove that its Turn and tools have stopped;
continue observing the Turn's terminal state.

Managed Endpoint Invocation command receipts also expose `input_id`, `input_status`
and `input_delivery_status`. Their command can remain `submitted` until the native Input
is applied or rejected. Managed Turns do not expose a separate `resume` operation.
SSE disconnects leave execution running; use the last applied cursor to reconnect.
See the [Managed Session API](/v2/en/service/claude-session-api) for native resources and controls.
