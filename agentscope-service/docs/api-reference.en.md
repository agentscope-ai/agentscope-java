# AgentScope Service API Reference

[中文](api-reference.md) · [API quickstart](api-quickstart.en.md) · [Events and streaming](events-and-streaming.en.md) · [Project overview](../../README.md)

This reference describes the current repository implementation of the public Managed Session protocol and the control-plane API catalog. The Session protocol revision is `agentscope-service-2026-10-19-draft3`. Query the running deployment's capabilities before integration; a source declaration does not guarantee a deployed endpoint or a supported feature.

## Contents

- [API layers](#api-layers)
- [Authentication and conventions](#authentication)
- [Supported Session API](#supported-session-api)
- [Create and retrieve Sessions](#sessions)
- [Send messages and control work](#events-write)
- [Platform extension: Turn and Input queries](#turns-and-inputs)
- [Platform extension: final output](#final-output)
- [History, SSE, and event types](#observation)
- [Pagination](#pagination)
- [Errors and limits](#errors)
- [Agent and resource management](#management)
- [Control-plane route catalog](#management-route-catalog)
- [Machine protocols, compatibility, and unsupported features](#boundaries)
- [Implementation sources](#implementation-sources)

<a id="api-layers"></a>
## Session/Events compatibility and platform extensions

The quickstart follows the conversation pattern in the [Claude quickstart](https://platform.claude.com/docs/en/managed-agents/quickstart) and [event guide](https://platform.claude.com/docs/en/managed-agents/events-and-streaming): create a Session, open its stream, send ordinary `user.message`, consume `agent.message` / `session.status_idle`, and use history to recover observation while checking `session.error`. Compatibility remains a verified `candidate` subset.

| Layer | Scope | How to use it |
| --- | --- | --- |
| Session/Events compatibility subset | Session create/get, Events send/list/stream, ordinary text and interrupt | Main conversation flow; no Turn ID, platform intent or response-selection header required |
| Platform management | Agent, Environment, Application, Namespace and other resources | This project's `/api/v1/...` and `/api/...`; no Claude-style `/v1/agents` or `/v1/environments` aliases currently exist |
| Platform execution extensions | `/capabilities`, `/turns/**`, targeted steer/inject/cancel, explicit `new_turn`, independent final output | Use when needed; `AgentScope-Extensions: turn-inputs-v1` selects platform events/linkage |

`/turns/{turn_id}/output` is this project's independent result-read extension, not a required step in a Claude Session/Events quickstart. Ordinary `user.message` requires an idle Session in this subset; explicit queueing and targeted inputs use platform extensions. The first release does not implement every upstream Agent, Environment, lifecycle, tool-confirmation, workflow, budget or delta capability.

<a id="authentication"></a>
## Authentication and conventions

Use the public Gateway URL as the base URL, for example `http://localhost:18080` in explicit local development mode. Paths below are relative to that URL. `{session_id}` and other braces mean a URL-encoded path parameter, not literal braces. Send JSON in UTF-8. Management fields are generally camelCase; Session fields are snake_case.

| Header | Meaning |
| --- | --- |
| `Authorization: Bearer <jwt>` | User JWT; use for management and authorized Sessions |
| `X-API-Key: <key>` | Application credential for granted Session targets/scopes |
| `X-AgentScope-Tenant` | Tenant selector, default examples use default |
| `X-AgentScope-Namespace` | Namespace selector; subject to membership and resource grants |
| `Content-Type: application/json` | JSON request bodies |
| `Idempotency-Key` | Recommended for Session creation and all event writes; required for explicit intents; 1–256 characters |
| `AgentScope-Extensions: turn-inputs-v1` | Select platform Turn/Input metadata and events in responses |
| `anthropic-version: 2023-06-01` | Optional recognized compatibility header; other values are rejected |
| `anthropic-beta: managed-agents-2026-04-01` | Optional recognized compatibility header; not a capability grant |


`Authorization` and `X-API-Key` are mutually exclusive on `/v1/sessions`. User JWTs come from `POST /api/auth/login` with `{"username":"...","password":"..."}`; the successful response contains `token`. Application keys are created by the Application owner and carry explicit scopes and resource targets. They do not grant access to arbitrary management resources or impersonate a human approver.

Scope headers select context; they do not grant access. Session creation requires invoke permission on the selected Agent and access to selected resources. Event reads, Turn/Input queries and final output require read permission. Message writes require invoke; cancel/interrupt requires cancel. Every retry rechecks the caller. SSE read permissions are periodically rechecked.

`BUILDER_LOCAL_DEV=true` uses an explicit development identity. An empty credential is valid only when that mode is enabled by the deployment. Internal service tokens, Environment worker keys and Task tokens have separate trust boundaries and are not substitutes for a caller credential.

Compatibility headers are optional. Unsupported versions, beta values, `anthropic-workspace-id`, duplicate selection headers and unknown extension values return 400. These headers do not change the platform product name or promise full compatibility with another service.

Successful Session creation and event submission currently return **200**. Control-plane create operations usually return **201**, with operation-specific envelopes. Do not apply a universal `{data: ...}` envelope to management responses. Public Session responses include a `request-id` response header; Session error bodies also include `request_id`.

<a id="supported-session-api"></a>
## Supported Session API

### Session/Events compatibility subset

| Method | Path | Purpose |
| --- | --- | --- |
| `POST` | `/v1/sessions` | Create and freeze a Session |
| `GET` | `/v1/sessions/{session_id}` | Retrieve the Session |
| `POST` | `/v1/sessions/{session_id}/events` | Atomically accept user events |
| `GET` | `/v1/sessions/{session_id}/events` | Page event history |
| `GET` | `/v1/sessions/{session_id}/events/stream` | Observe future processed events |

### Platform execution extensions

| Method | Path | Purpose |
| --- | --- | --- |
| `GET` | `/v1/sessions/{session_id}/capabilities` | Inspect protocol and limits |
| `GET` | `/v1/sessions/{session_id}/turns` | List admitted Turns |
| `GET` | `/v1/sessions/{session_id}/turns/{turn_id}` | Read current Turn status |
| `GET` | `/v1/sessions/{session_id}/turns/{turn_id}/capabilities` | Read Turn interaction capabilities |
| `GET` | `/v1/sessions/{session_id}/turns/{turn_id}/inputs` | List Turn Inputs |
| `GET` | `/v1/sessions/{session_id}/turns/{turn_id}/inputs/{input_id}` | Read one Input |
| `GET` | `/v1/sessions/{session_id}/turns/{turn_id}/output` | Read committed final messages |


This list includes entry-filter restrictions, not just Controller declarations. `GET /v1/sessions` (list), Session updates/lifecycle, snapshot/usage/resource endpoints and public file/checkpoint/webhook operations are not enabled in this protocol release. Unknown fields and query options are rejected instead of silently ignored. `GET` requests accept the SDK transport flag `beta=true` where applicable; it is not a business filter.

<a id="sessions"></a>
## Create and retrieve Sessions

### POST /v1/sessions

```json
{
  "agent": {"type": "agent", "id": "<agent_id>", "version": 1},
  "environment_id": "<environment_id>",
  "title": "A product conversation",
  "metadata": {"source": "my-application"}
}
```


| Field | Required | Contract |
| --- | --- | --- |
| `agent` | Yes | Agent ID string, or `{type:"agent", id, version?}`; IDs have at most 256 characters, version is a positive integer |
| `environment_id` | No | Authorized runnable Environment ID; otherwise resolve from Agent/deployment configuration |
| `title` | No | String of at most 500 characters, or null |
| `metadata` | No | Object with at most 16 string pairs; keys ≤64 and values ≤512 characters |

Only these four top-level fields are accepted. In particular, do not send `resources`, `memory_store_ids`, `agent_overrides`, or an inline Agent definition. Bind configuration to the Agent through management APIs. Send a stable `Idempotency-Key` for retries; when omitted the server generates a key, so the client cannot reliably replay that request by its own identity.

The response is a direct Session resource with `type:"session"`, `id`, frozen `agent` reference, `environment_id`, `title`, `metadata`, `status`, creation/update timestamps and measured `usage` where available. The selected Agent version is frozen for the Session. With `turn-inputs-v1`, `agentscope_service` also carries runtime kind, lifecycle/configuration revisions and execution authority. Empty `resources`, `stats`, or a null `budget` are placeholders, not feature availability.

A Session uses the platform service account for execution while retaining the original caller's authorization. It does not accept arbitrary execution identities. The first protocol release rejects nonempty Skills/multiagent configurations, client-side tool confirmation/external results, unsupported tool names and stdio MCP. HTTP MCP requires a matching named toolset and a non-interactive enabled-tool policy.

### GET /v1/sessions/{session_id}

Returns current Session state under read authorization. Session status summarizes the conversation (for example `idle` or `running`); it is not the precise outcome of a particular Turn. An idle boundary can precede execution of another queued Turn. Ordinary compatible conversations follow `agent.message`, `session.status_idle` and `session.error` in the stream/history; use the platform Turn extension when precise tracking of a designated Turn is needed.

### Platform extension: GET /v1/sessions/{session_id}/capabilities

Returns `session_id`, `runtime_kind`, `protocol_revision`, `compatibility`, `extensions`, `features`, `capabilities`, `limits`, `input_event_types`, `intents` and `content_types`. The current response advertises text, `user.message`/`user.interrupt`, `new_turn`/`steer`/`inject`/`cancel`, `turn-inputs-v1:true` and `turn_output:true`. Durable events, snapshots, Session list/update/lifecycle and other future features remain false. Compatibility level is `candidate`, not a blanket SDK compatibility guarantee.

<a id="events-write"></a>
## Send messages and control work

### POST /v1/sessions/{session_id}/events

```json
{
  "events": [{
    "type": "user.message",
    "content": [{"type": "text", "text": "Summarize the incident."}]
  }]
}
```

This is the ordinary compatibility request for an idle Session. To use the platform queueing extension, add `"agentscope_service":{"intent":"new_turn"}` to the Event and supply an idempotency key. The interaction table below distinguishes ordinary input from explicit platform intents.


The request has exactly one top-level field, `events`, with **1–50 objects**, and a maximum serialized/request-body size of **1 MiB**. A `user.message` contains a nonempty `content` array of `{type:"text",text:"..."}` blocks. Each text must be nonempty; images, file blocks, tool results and additional block fields are not accepted. A `user.interrupt` has no content field.

| Input | Meaning | Target / key |
| --- | --- | --- |
| Plain `user.message` | Start a Turn only while the Session is idle, with no active/queued work | No explicit target; key recommended |
| `user.message` + `intent:new_turn` | Create a Turn, queueing behind unfinished work | No `turn_id` allowed; key required |
| `user.message` + `intent:steer` | Apply guidance at a supported boundary of an existing Turn | Explicit `turn_id`; key required |
| `user.message` + `intent:inject` | Add context to an existing Turn at a supported boundary | Explicit `turn_id`; key required |
| Plain `user.interrupt` | Resolve and interrupt current work at admission; idle may be a no-op | No explicit target; key recommended |
| `user.interrupt` + `intent:cancel` | Request cancellation of a particular Turn | Explicit `turn_id`; key required |

The extension accepts only `intent` and `turn_id`. `current` and `latest` are invalid explicit targets. An output-selection header is not needed to submit an explicit intent: its own receipt contains platform metadata. The header is needed to select platform information on later standard history/SSE reads.

Examples of input injection and explicit cancellation:

```json
{
  "events": [{
    "type": "user.message",
    "content": [{"type": "text", "text": "Also include the confirmed completion time."}],
    "agentscope_service": {"intent": "inject", "turn_id": "<turn_id>"}
  }]
}
```

```json
{
  "events": [{
    "type": "user.interrupt",
    "agentscope_service": {"intent": "cancel", "turn_id": "<turn_id>"}
  }]
}
```


Use a distinct stable `Idempotency-Key` for each operation. A cancel receipt acknowledges a request, not immediate physical tool termination. Query Turn/Input state until its actual outcome is known.

### Acceptance receipt and idempotency

The following illustrates platform acceptance fields selected by `turn-inputs-v1` or an explicit intent; an ordinary receipt omits `agentscope_service`:

```json
{
  "data": [{
    "id": "<event_id>",
    "type": "user.message",
    "content": [{"type": "text", "text": "Summarize the incident."}],
    "processed_at": null,
    "agentscope_service": {
      "schema_version": 1,
      "session_id": "<session_id>",
      "event_id": "<event_id>",
      "turn_id": "<turn_id>",
      "admitted_at": "2026-10-19T12:00:00Z",
      "intent": "new_turn",
      "status": "accepted",
      "input_event_ids": ["<event_id>"],
      "receipt": {"type": "turn", "id": "<turn_id>", "status": "accepted"}
    }
  }]
}
```


Responses preserve request order. Explicit interactions add `input_id`, `input_type` and an Input receipt instead of a Turn receipt. Retain Event, Turn and Input IDs separately.

Admission atomically commits identities, quota effects, receipt and pending delivery. An invalid/conflicting item rolls back the batch. Supported ordinary batches are one message, one interrupt, or `[interrupt, message]` in that order. Multiple explicit intents can be accepted atomically up to the overall limit; do not mix arbitrary ordinary and explicit events or assume every combination is accepted.

Same caller/Session/key plus identical request content returns the **frozen original receipt**, even after execution or input closure. Reusing a key with changed content returns 409. A fresh key performs fresh mutable checks. A timeout or 5xx does not prove rejection; retry the same body/key. GET endpoints return current state, unlike the original receipt. Keys for unrelated operations are not interchangeable.

<a id="turns-and-inputs"></a>
## Platform extension: Turn and Input queries

### GET /v1/sessions/{session_id}/turns

| Query | Default | Values |
| --- | --- | --- |
| `limit` | 100 | 1–256 |
| `order` | `desc` | `asc`, `desc` |
| `after` | none | Opaque `next_cursor` from this list/query |
| `status` | none | `accepted`, `preparing`, `running`, `waiting`, `waiting_tool`, `completed`, `failed`, `cancelled` |
| `input_event_id` | none | Filter by the admitted Event ID |

Returns `{data:[...],has_more,next_cursor}`. A Turn includes `id`, `type:"turn"`, `session_id`, `created_at`, `admission_sequence`, `status`, `delivery_status`, `inputs_open`, `input_event_ids`, execution/publication sequence when assigned, timestamps, error, `cancel_requested`, `output_available` and `output_url`. Nullable timestamps or missing publication information mean those facts are not yet available.

`accepted` means admitted; `waiting_tool` means waiting for confirmed tool execution, not completed. A typical path is `accepted → preparing/running → waiting_tool → running → completed`; `failed` and `cancelled` are also terminal. Later Turns wait for unfinished earlier Turns. A tool's completion does not itself finish the model's Turn.

### GET /v1/sessions/{session_id}/turns/{turn_id}

Returns the current Turn resource with the same fields. `output_available` indicates committed final messages; it is not a synonym for terminal status.

### GET /v1/sessions/{session_id}/turns/{turn_id}/capabilities

Returns the current permitted interaction capabilities for that Turn. Check these before steering/injecting/cancelling; capability discovery is not a reservation, so admission may still reject a racing state change.

### GET /v1/sessions/{session_id}/turns/{turn_id}/inputs

Accepts `limit` (100, range 1–256), `after`, `status` (`accepted`, `applied`, `rejected`) and `event_id`. Returns `{data,has_more,next_cursor}`. `GET .../inputs/{input_id}` returns one Input after verifying its Session/Turn association.

An Input includes `id`, `type:"input"`, `session_id`, `turn_id`, `input_type`, `sequence`, `status`, `delivery_status`, its Event link and admission/application times where available. Rejected inputs may include `rejection_reason`; applied resolutions may include `resolution_reason`. Accepted/delivered is not applied. Persist and query the Input ID rather than inferring success from the write receipt or a Redis cursor.

<a id="final-output"></a>
## Platform extension: final output

### GET /v1/sessions/{session_id}/turns/{turn_id}/output

Accepts `limit` (default 100, 1–256) and `after` (this output collection's `next_cursor`). Requires read permission; no SSE, snapshot or output-selection header is required.

```json
{
  "type": "turn_output",
  "session_id": "<session_id>",
  "turn_id": "<turn_id>",
  "turn_status": "completed",
  "output_available": true,
  "output_event_ids": ["<assistant_event_id>"],
  "data": [{
    "id": "<assistant_event_id>",
    "type": "agent.message",
    "processed_at": "2026-10-19T12:00:10Z",
    "content": [{"type": "text", "text": "Here is the summary."}],
    "agentscope_service": {"schema_version": 1, "session_id": "<session_id>", "turn_id": "<turn_id>", "item_id": "<item_id>"}
  }],
  "next_cursor": null,
  "has_more": false
}
```


Only committed, sealed final assistant messages appear, in output order. The collection remains stable once sealed. Before output exists, return 200 with `output_available:false`, `data:[]`, `output_event_ids:[]`, `has_more:false` and a null cursor. Terminal without output is possible; it is different from an existing output projection that is temporarily unreadable, which returns retryable 503.

Follow `next_cursor` with `after` while `has_more` is true. IDs in `output_event_ids` identify that page's messages. Reading output never submits or resumes a Turn. Tool events and intermediate assistant messages belong in history, not in the final-output collection.

<a id="observation"></a>
## History, SSE, and event types

### GET /v1/sessions/{session_id}/events

| Query | Contract |
| --- | --- |
| `limit` | Default 100, 1–256 |
| `order` | `asc` (default) or `desc` |
| `page` | Opaque `next_page` from a previous history response |
| `types[]` | Repeated exact event-type filters |
| `created_at[gt]`, `[gte]`, `[lt]`, `[lte]` | RFC 3339 instants, compared to event **processed_at**; pending events have no processing timestamp |
| `beta` | Only `true`; optional SDK transport flag |

Returns `{data:[...],next_page:<cursor-or-null>}`. The first page fixes an upper boundary; later pages retain that boundary and the projected version of each Event. Re-query without `page` to refresh. Cursors bind the Session, caller, filter/order and representation; do not change filters mid-traversal. `limit` may change. Use URL encoding for brackets and timestamps, for example `curl -G --data-urlencode 'types[]=agent.message' ...`.

An input Event initially has `processed_at:null`. When processed, its identity stays the same and the value is updated. Consumers should merge by Event ID and allow processing-state updates, not discard every duplicate ID as redundant.

### GET /v1/sessions/{session_id}/events/stream

Returns `text/event-stream`, with `event: <type>` and JSON `data: ...`. It captures a boundary at connection and emits **future processed events** only, plus comment heartbeats. It does not return pending acceptance records or replay old history. A completed Turn does not close the Session stream.

No `Last-Event-ID`, `after`, preview, deltas or durable resume is supported. The only optional query is `beta=true`. Open/buffer a stream, fetch history, and merge Events to recover your view. Disconnecting or pressing Ctrl+C does not cancel work. For a final answer without streaming, poll the Turn and read `/output`.

### Event families

| Family | Meaning |
| --- | --- |
| `user.message`, `user.interrupt` | Accepted input with a stable public ID; processing time becomes available later |
| `agent.message` | Public assistant text content |
| `agent.tool_use`, `agent.tool_result` | Supported native tool call and its correlated result (`tool_use_id`) |
| `agent.mcp_tool_use`, `agent.mcp_tool_result` | HTTP MCP call and correlated result (`mcp_tool_use_id`) |
| `span.model_request_start`, `span.model_request_end` | Correlated model request spans; end usage is emitted only when all required counts are measured |
| `session.status_running`, `session.status_idle` | Current implementation's conversation state boundaries |
| `session.error` | Public error information for failed execution |
| `agentscope_service.turn.*` | Platform Turn status and terminal output links; selected by `turn-inputs-v1` |
| `agentscope_service.input.*` | Platform input admission/application/rejection notifications; selected by `turn-inputs-v1` |

Event JSON includes `id`, `type`, `processed_at` and type-specific content. Tool results include content and `is_error`; preserve tool-use/result correlation. The `agentscope_service` object carries Session/Turn/Input context when selected. Standard input and output Events are not native runtime journal envelopes. Private reasoning, credentials and raw checkpoints are not public events. No client approval or client-submitted tool-result event is enabled.

<a id="pagination"></a>
## Pagination

| Collection | Request cursor | Response continuation | Completion |
| --- | --- | --- | --- |
| Session event history | `page` | `next_page` | null |
| Turns | `after` | `next_cursor`, `has_more` | false / null |
| Turn Inputs | `after` | `next_cursor`, `has_more` | false / null |
| Turn final output | `after` | `next_cursor`, `has_more` | false / null |
| Management lists | Per operation | Often `items`; product lists may be arrays with `X-Total-Count` and `limit/offset` | See handler contract |

Opaque cursors are not offsets, Event IDs, Redis Stream IDs or SSE resume tokens. Never interchange them across endpoints, Sessions, Turns, callers or filters. Turn/Input lists page admitted resources and read their current state; do not assume they have the history endpoint's frozen-version semantics.

<a id="errors"></a>
## Errors and limits

Public `/v1/sessions` errors use this shape (illustrative IDs):

```json
{
  "type": "error",
  "error": {
    "type": "conflict_error",
    "message": "Invalid request",
    "code": "idempotency_conflict"
  },
  "request_id": "req_<request_id>"
}
```


`error.code` is selected when the output extension is requested or the write contains an explicit platform intent. Standard errors can omit it. Retain the request ID for diagnostics. Control-plane APIs currently have their own JSON/text error formats, often `{"error":"..."}`; do not parse them as Session errors.

| HTTP | Session error type | Typical meaning |
| --- | --- | --- |
| 400 | `invalid_request_error` | Invalid field/query, unsupported capability or missing explicit-intent key |
| 401 | `authentication_error` | Missing/invalid credential |
| 403 | `permission_error` | Current caller is not authorized |
| 404 | `not_found_error` | Session/Turn/Input is absent or inaccessible under its association |
| 409 | `conflict_error` | Changed idempotent content, terminal/closed target, inactive Session, competing request |
| 410 | `invalid_request_error` | Expired referenced resource where applicable; not a way to enable a future API |
| 413 | `request_too_large` | Request body exceeds the supported size |
| 429 | `rate_limit_error` | Admission quota or SSE capacity reached |
| 503 | `api_error` | Database/service unavailable or committed projection temporarily unreadable |

Useful platform codes include `unsupported_capability`, `invalid_event`, `incompatible_query`, `invalid_cursor`, `idempotency_key_required`, `idempotency_conflict`, `request_in_progress`, `session_inactive`, `turn_terminal`, `admission_limited` and `service_unavailable`. Other protocol conflict codes may be returned. Check HTTP status and code rather than matching human-readable text.

Limits advertised by capabilities are 50 Events/request, 1,048,576 bytes/event-write request and 256 objects/page. The maximum page size is not the default (100). Session metadata/title limits are above. Execution concurrency, queued work and SSE connection limits are deployment settings. For 409 coordination conflicts, 429, 5xx or uncertain transport results, retry with backoff and the original write body/key; fix validation/authorization errors before retrying.

<a id="management"></a>
## Agent and resource management

These operations run in the control plane with user/namespace/resource authorization. They configure the Agent before Session creation. The route catalog below includes method/path and a link to the exact handler for request structs, responses and operation-specific authorization. Bodies and response envelopes are not uniform.

### Agent catalog and definitions

`POST /api/v1/agents` accepts:

```json
{
  "tenant": "default",
  "namespace": "default",
  "agentKey": "support-assistant",
  "displayName": "Support Assistant",
  "description": "Answers product questions",
  "binding": {"kind": "managed"},
  "definition": {
    "name": "Support Assistant",
    "system": "Answer clearly and use only confirmed facts.",
    "maxIters": 8,
    "defaultEnvironmentId": "<environment_id>",
    "tools": [{"type": "agent_toolset", "defaultConfig": {"enabled": false}}]
  }
}
```


| Field | Contract |
| --- | --- |
| `agentKey` | Required logical Agent key within tenant/namespace |
| `tenant`, `namespace` | Defaults are deployment scope; authorized scope can override body selectors |
| `displayName`, `description` | Catalog presentation |
| `binding.kind` | `managed` for this Session flow; Hosted has separate configuration; External uses registration |
| `definition` | Required for Managed binding; `name` falls back to displayName |
| `definition.system` | Agent instructions |
| `definition.model` | Optional server-supported model selector; omitted in quickstart to use deployment default |
| `definition.maxIters` | Positive iteration bound; creation defaults to 20 when nonpositive/omitted |
| `definition.defaultEnvironmentId` | Optional authorized Environment; local development may provision a default |
| `definition.tools`, `mcpServers` | Toolset and HTTP MCP configuration; must also satisfy Session protocol validation |
| `definition.workspaceId`, `workspaceBinding`, defaults for Vault/Memory | Resource configuration; eligibility still checked during Session admission |

A successful runnable creation returns `{agent,binding,policy,definition}`; retrieve the ID from `agent.id`. Omitting `binding` creates only a catalog record. `GET /api/v1/agents` returns `{items:[...]}` and supports tenant/namespace/status/includeArchived/limit filters. Retrieve an Agent with `GET /api/v1/agents/{agent_id}`.

`GET /api/v1/agents/{agent_id}/definition` returns its portable definition. `PATCH .../definition` takes definition fields, requires `name` and the current positive `version`, and creates the next definition version. `GET .../versions` and `GET .../versions/{version}` expose version history. Existing Sessions keep their frozen definition. Catalog `PATCH /api/v1/agents/{agent_id}` and binding edits also require their respective current `version`; use GET before updating.

Definition fields are documented in [ManagedDefinitionInput](../service-controlplane/internal/product/managed_definition.go); catalog creation and updates are in [agent_catalog_handler.go](../service-controlplane/internal/httpapi/agent_catalog_handler.go). A field supported by the configuration store is not automatically supported by the first public Session release.

### Environments

`POST /api/environments` takes `{name,type,config}` and returns the Environment directly (`id`, `name`, `type`, `config`, ownership and timestamps). `GET /api/environments` returns an array, with product pagination/count headers. Local example:

```json
{"name":"Local API Environment","type":"local","config":{}}
```

Local environments require deployment policy allowing local execution. `cloud`, `self_hosted` and `remote` have provider-specific configuration; `sandbox` normalizes to `cloud`. Use the [Environment handler](../service-controlplane/internal/product/handlers_env.go) and [deployment guide](../deploy/README.md) for endpoint/domain/template/credential constraints instead of changing only the type in this example. Creating an Environment does not itself provision a Worker or prove provider connectivity.

### Applications and credentials

Create an Application with `POST /api/v1/applications` using the first example, then create its credential with `POST /api/v1/applications/{application_id}/credentials` using the second:

```json
{
  "name": "Product Integration",
  "tenant": "default",
  "namespace": "default"
}
```

```json
{
  "name": "backend-session-client",
  "scopes": ["invoke", "read", "cancel"],
  "targets": [{"type": "agent", "id": "<agent_id>"}]
}
```


Responses are `{application:...}` and `{credential:...,apiKey:"..."}` respectively. Save `apiKey` at creation; later list responses omit the plaintext key. Target grants identify the Agent/resource without a version; select the version at Session creation. The Application owner manages credentials, optional `expiresAt` must be in the future, and each supported scope/target is validated. `DELETE .../credentials/{credential_id}` revokes it. Use `X-API-Key` for Session requests and keep human-management JWTs separate.

Application members and limits do not automatically isolate individual business end users. Your integration must choose the appropriate Application/Session ownership and resource grants. See [application schema](../service-controlplane/internal/controlplane/model/application.go) and [credential handler](../service-controlplane/internal/httpapi/service_session_auth.go).

### HTTP MCP configuration

A URL server and its named toolset must match. For example, the following is a definition fragment; replace the URL and configure actual provider access separately:

```json
{
  "mcpServers": [{"name": "knowledge", "url": "https://tools.example.com/mcp"}],
  "tools": [
    {"type": "agent_toolset", "defaultConfig": {"enabled": false}},
    {
      "type": "mcp_toolset",
      "mcpServerName": "knowledge",
      "defaultConfig": {"enabled": true, "permissionPolicy": {"type": "always_allow"}}
    }
  ]
}
```

Brain directly discovers and pins MCP descriptors before inference; actual MCP calls execute asynchronously in Hands. A configured URL is not evidence that its catalog or credentials are valid; validate with the actual deployment. Supported native tool configuration names in this protocol are `bash`, `read`, `write`, `edit`, `glob`, `grep`, `web_fetch`, and `web_search`. Enabled tools require `always_allow` in this release; a disabled tool may retain another policy. This is a configuration constraint, not a recommendation to enable every tool for every Agent.

### Resource request fields

These are request-body field guides, not complete nested schemas. For optional fields and provider-specific validation, use the linked handler in the route catalog. A mutation's `version`/`expectedVersion` is the current resource version, not an Agent definition version or Session protocol revision.

| Operation | Body fields / behavior |
| --- | --- |
| `POST /api/workspaces` | Required nonempty `name`; optional `description`, `tools`, `mcpServers`, `skills`; returns the Workspace directly |
| `POST /api/memory-stores` | `name`, `description`; path-based `PUT .../memories/*path` uses `content`, `summary` |
| `POST /api/vaults` | `displayName`, `metadata` |
| `POST /api/vaults/{id}/credentials` | `type`, `label`, `target`, `secret`; store actual secrets through the authorized API |
| `POST /api/channels` / `PUT /api/channels/{id}` | `channelId`, `type`, `dmScope`, `defaultAgentId`, `disabled`, `properties`, `bindings`; provider settings and identity policy apply |
| `POST /api/deployments` | `name`, `agentId`, `agentVersion`, `environmentId`, `triggerType`, `cronExpression`; scheduled and provider triggers have separate validation |
| `POST /api/v1/orchestration-definitions` | OrchestrationDefinition schema; see [definition model](../service-controlplane/internal/controlplane/model/orchestration.go) and the handler for validation/publish/run boundaries |
| `POST/PATCH /api/v1/automations[/{id}]` | `name`, `description`, `enabled`, `execution`, `triggers`, or supported `triggerType/triggerConfig/actionType/actionConfig`; updates use `expectedVersion` |

### Other resource families

| Family | Purpose / request schema source |
| --- | --- |
| Namespace / resource access | Membership, groups, grants, requests, audit: [access](../service-controlplane/internal/httpapi/access_management.go), [resource access](../service-controlplane/internal/httpapi/resource_groups.go) |
| Workspace / Skills / tool catalog | Definition files, published revisions and tool configuration: [workspaces](../service-controlplane/internal/product/handlers_workspaces.go), [Agent workspace](../service-controlplane/internal/product/handlers_workspace.go) |
| Memory stores | Store creation and path-based memory access: [handler](../service-controlplane/internal/product/handlers_memory.go) |
| Vault / OAuth | Credentials and connection/authorization flows: [Vault](../service-controlplane/internal/product/handlers_vault.go), [OAuth](../service-controlplane/internal/product/oauth_connector.go) |
| Channels / Deployments | Provider bindings, delivery activity, scheduled triggers: [Channels](../service-controlplane/internal/product/handlers_channels.go), [Deployments](../service-controlplane/internal/product/handlers_deploy.go) |
| Teams / Issues / AgentTasks | Collaboration, assignments, comments, decisions and execution attempts: route-handler links below |
| Workflow definitions / runs | Validate/publish definitions; start, inspect, signal and control runs: route-handler links below |
| Runtime profiles / pools / hosts | Hosted capacity and configuration; machine lifecycle is a separate protocol |

<a id="management-route-catalog"></a>
## Control-plane route catalog

The catalog below resolves paths from current registration code for a store-backed deployment with product APIs mounted. Each handler link leads to its actual Go implementation, including JSON request types, response envelopes, checks and status codes. It is an endpoint index accompanying the detailed Session/resource contracts above, not an OpenAPI schema or a promise that every optional feature is enabled. Kubernetes-only resources are marked separately; machine and compatibility routes follow later.

### Accounts and authentication

| Method | Path | Handler / contract source |
| --- | --- | --- |
| `GET` | `/api/admin/access-audit` | [accountAudit](../service-controlplane/internal/product/account_management.go#L233) |
| `GET` | `/api/admin/integrations/github` | [githubAppGet](../service-controlplane/internal/product/oauth_github.go#L80) |
| `PUT` | `/api/admin/integrations/github` | [githubAppSave](../service-controlplane/internal/product/oauth_github.go#L98) |
| `GET` | `/api/admin/users` | [adminListUsers](../service-controlplane/internal/product/handlers_admin.go#L51) |
| `POST` | `/api/admin/users` | [adminCreateUser](../service-controlplane/internal/product/handlers_admin.go#L63) |
| `DELETE` | `/api/admin/users/{id}` | [adminDeleteUser](../service-controlplane/internal/product/handlers_admin.go#L206) |
| `PATCH` | `/api/admin/users/{id}/password` | [adminResetPassword](../service-controlplane/internal/product/handlers_admin.go#L144) |
| `PATCH` | `/api/admin/users/{id}/roles` | [adminUpdateRoles](../service-controlplane/internal/product/handlers_admin.go#L190) |
| `PATCH` | `/api/admin/users/{id}/status` | [adminAccountStatus](../service-controlplane/internal/product/account_management.go#L123) |
| `GET` | `/api/auth/dev-session` | [localDevSession](../service-controlplane/internal/product/local_dev.go#L39) |
| `POST` | `/api/auth/login` | [login](../service-controlplane/internal/product/handlers_auth.go#L37) |
| `POST` | `/api/auth/logout` | [logoutAccount](../service-controlplane/internal/product/account_management.go#L333) |
| `GET` | `/api/auth/me` | [me](../service-controlplane/internal/product/handlers_auth.go#L83) |
| `GET` | `/api/oauth/mcp/callback/{connectionId}` | [oauthCallback](../service-controlplane/internal/product/oauth_callback.go#L60) |
| `GET` | `/api/oauth/providers/github` | [githubProviderStatus](../service-controlplane/internal/product/oauth_github.go#L70) |
| `POST` | `/api/user/change-password` | [changePassword](../service-controlplane/internal/product/handlers_auth.go#L101) |
| `DELETE` | `/api/user/channel-connections` | [removeMyChannelIdentity](../service-controlplane/internal/product/account_connections.go#L71) |
| `GET` | `/api/user/channel-connections` | [myChannelConnections](../service-controlplane/internal/product/account_connections.go#L27) |
| `DELETE` | `/api/user/channel-subscriptions/{subscriptionId}` | [unsubscribeMyChannel](../service-controlplane/internal/product/account_connections.go#L88) |
| `GET` | `/api/user/login-sessions` | [listLoginSessions](../service-controlplane/internal/product/account_management.go#L278) |
| `POST` | `/api/user/login-sessions/revoke-others` | [revokeOtherLoginSessions](../service-controlplane/internal/product/account_management.go#L313) |
| `DELETE` | `/api/user/login-sessions/{sessionId}` | [revokeLoginSession](../service-controlplane/internal/product/account_management.go#L301) |
| `GET` | `/api/user/profile` | [profile](../service-controlplane/internal/product/handlers_auth.go#L94) |
| `PUT` | `/api/user/profile` | [updateProfile](../service-controlplane/internal/product/account_management.go#L262) |

### Namespace, access and preferences

| Method | Path | Handler / contract source |
| --- | --- | --- |
| `GET` | `/api/v1/access/accounts` | [platformAccountDirectory](../service-controlplane/internal/httpapi/access_management.go#L120) |
| `GET` | `/api/v1/access/audit` | [namespaceAudit](../service-controlplane/internal/httpapi/access_management.go#L205) |
| `GET` | `/api/v1/access/users/{accountId}/namespaces` | [accountNamespaces](../service-controlplane/internal/httpapi/access_management.go#L186) |
| `POST` | `/api/v1/entity-identities:resolve` | [resolveEntityIdentities](../service-controlplane/internal/httpapi/entity_identity_handler.go#L100) |
| `GET` | `/api/v1/me/namespaces` | [listMyNamespaces](../service-controlplane/internal/httpapi/namespace_access.go#L417) |
| `GET` | `/api/v1/me/navigation` | [navigationAccess](../service-controlplane/internal/httpapi/workspace_rbac.go#L187) |
| `GET` | `/api/v1/me/preferences` | [getMyPreferences](../service-controlplane/internal/httpapi/access_management.go#L259) |
| `PUT` | `/api/v1/me/preferences` | [updateMyPreferences](../service-controlplane/internal/httpapi/access_management.go#L271) |
| `GET` | `/api/v1/me/scope` | [getCurrentScope](../service-controlplane/internal/httpapi/scope_handler.go#L74) |
| `GET` | `/api/v1/namespaces` | [listManagedNamespaces](../service-controlplane/internal/httpapi/access_management.go#L57) |
| `POST` | `/api/v1/namespaces` | [createNamespace](../service-controlplane/internal/httpapi/namespace_access.go#L446) |
| `GET` | `/api/v1/namespaces/{namespaceName}` | [getNamespaceAccess](../service-controlplane/internal/httpapi/namespace_access.go#L505) |
| `PUT` | `/api/v1/namespaces/{namespaceName}` | [updateNamespaceAccess](../service-controlplane/internal/httpapi/namespace_access.go#L511) |
| `GET` | `/api/v1/namespaces/{namespaceName}/accounts` | [namespaceAccountDirectory](../service-controlplane/internal/httpapi/access_management.go#L125) |
| `GET` | `/api/v1/namespaces/{namespaceName}/audit` | [namespaceAudit](../service-controlplane/internal/httpapi/access_management.go#L205) |
| `GET` | `/api/v1/namespaces/{namespaceName}/groups` | [listAccessGroups](../service-controlplane/internal/httpapi/resource_groups.go#L41) |
| `PUT` | `/api/v1/namespaces/{namespaceName}/groups` | [updateAccessGroups](../service-controlplane/internal/httpapi/resource_groups.go#L88) |
| `POST` | `/api/v1/namespaces/{namespaceName}/import-template` | [importWorkflowTemplate](../service-controlplane/internal/httpapi/resource_templates.go#L81) |
| `GET` | `/api/v1/namespaces/{namespaceName}/requests` | [listAccessRequests](../service-controlplane/internal/httpapi/resource_groups.go#L140) |
| `POST` | `/api/v1/namespaces/{namespaceName}/requests` | [createAccessRequest](../service-controlplane/internal/httpapi/resource_groups.go#L155) |
| `POST` | `/api/v1/namespaces/{namespaceName}/requests/{requestId}/review` | [reviewAccessRequest](../service-controlplane/internal/httpapi/resource_groups.go#L198) |
| `GET` | `/api/v1/namespaces/{namespaceName}/resources` | [listNamespaceResources](../service-controlplane/internal/httpapi/resource_access.go#L223) |
| `GET` | `/api/v1/namespaces/{namespaceName}/resources/{kind}/{resourceId}/access` | [getResourceAccess](../service-controlplane/internal/httpapi/resource_access.go#L254) |
| `PUT` | `/api/v1/namespaces/{namespaceName}/resources/{kind}/{resourceId}/access` | [updateResourceAccess](../service-controlplane/internal/httpapi/resource_access.go#L307) |
| `GET` | `/api/v1/namespaces/{namespaceName}/shared-templates` | [sharedWorkflowTemplates](../service-controlplane/internal/httpapi/resource_templates.go#L30) |
| `POST` | `/api/v1/namespaces/{namespaceName}/transfer` | [transferNamespace](../service-controlplane/internal/httpapi/access_management.go#L228) |

### Applications and credentials

| Method | Path | Handler / contract source |
| --- | --- | --- |
| `GET` | `/api/v1/applications` | [listApplications](../service-controlplane/internal/httpapi/application_handler.go#L94) |
| `POST` | `/api/v1/applications` | [createApplication](../service-controlplane/internal/httpapi/application_handler.go#L67) |
| `GET` | `/api/v1/applications/{applicationId}` | [getApplication](../service-controlplane/internal/httpapi/application_handler.go#L115) |
| `PATCH` | `/api/v1/applications/{applicationId}` | [patchApplication](../service-controlplane/internal/httpapi/application_handler.go#L131) |
| `GET` | `/api/v1/applications/{applicationId}/credentials` | [listSessionCredentials](../service-controlplane/internal/httpapi/service_session_auth.go#L115) |
| `POST` | `/api/v1/applications/{applicationId}/credentials` | [createSessionCredential](../service-controlplane/internal/httpapi/service_session_auth.go#L52) |
| `DELETE` | `/api/v1/applications/{applicationId}/credentials/{credentialId}` | [revokeSessionCredential](../service-controlplane/internal/httpapi/service_session_auth.go#L149) |

### Agent catalog, definitions and runtime policies

| Method | Path | Handler / contract source |
| --- | --- | --- |
| `POST` | `/api/v1/agent-registrations/{agentId}/credentials/rotate` | [rotateAgentRegistrationCredential](../service-controlplane/internal/httpapi/agent_catalog_handler.go#L1145) |
| `DELETE` | `/api/v1/agent-registrations/{agentId}/credentials/{credentialId}` | [revokeAgentRegistrationCredential](../service-controlplane/internal/httpapi/agent_catalog_handler.go#L1172) |
| `GET` | `/api/v1/agent-runtime-policies/{agentId}` | [getAgentRuntimePolicy](../service-controlplane/internal/httpapi/orchestration_handler.go#L412) |
| `PUT` | `/api/v1/agent-runtime-policies/{agentId}` | [putAgentRuntimePolicy](../service-controlplane/internal/httpapi/orchestration_handler.go#L429) |
| `GET` | `/api/v1/agents` | [listCatalogAgents](../service-controlplane/internal/httpapi/agent_catalog_handler.go#L168) |
| `POST` | `/api/v1/agents` | [createCatalogAgent](../service-controlplane/internal/httpapi/agent_catalog_handler.go#L445) |
| `GET` | `/api/v1/agents/runtime-options` | [listAgentRuntimeOptions](../service-controlplane/internal/httpapi/agent_catalog_handler.go#L223) |
| `GET` | `/api/v1/agents/{agentId}` | [getCatalogAgent](../service-controlplane/internal/httpapi/agent_catalog_handler.go#L702) |
| `PATCH` | `/api/v1/agents/{agentId}` | [patchCatalogAgent](../service-controlplane/internal/httpapi/agent_catalog_handler.go#L732) |
| `GET` | `/api/v1/agents/{agentId}/bindings` | [listAgentBindings](../service-controlplane/internal/httpapi/agent_catalog_handler.go#L783) |
| `POST` | `/api/v1/agents/{agentId}/bindings` | [createAgentBinding](../service-controlplane/internal/httpapi/agent_catalog_handler.go#L796) |
| `PATCH` | `/api/v1/agents/{agentId}/bindings/{bindingId}` | [patchAgentBinding](../service-controlplane/internal/httpapi/agent_catalog_handler.go#L820) |
| `GET` | `/api/v1/agents/{agentId}/definition` | [getManagedAgentDefinition](../service-controlplane/internal/httpapi/agent_catalog_handler.go#L576) |
| `PATCH` | `/api/v1/agents/{agentId}/definition` | [patchManagedAgentDefinition](../service-controlplane/internal/httpapi/agent_catalog_handler.go#L655) |
| `POST` | `/api/v1/agents/{agentId}/definition` | [initializePortableDefinition](../service-controlplane/internal/httpapi/workspace_definition.go#L34) |
| `GET` | `/api/v1/agents/{agentId}/hosted-settings` | [getHostedAgentSettings](../service-controlplane/internal/httpapi/agent_catalog_handler.go#L909) |
| `PATCH` | `/api/v1/agents/{agentId}/hosted-settings` | [patchHostedAgentSettings](../service-controlplane/internal/httpapi/agent_catalog_handler.go#L1035) |
| `GET` | `/api/v1/agents/{agentId}/instances` | [listCatalogAgentInstances](../service-controlplane/internal/httpapi/agent_catalog_handler.go#L1127) |
| `GET` | `/api/v1/agents/{agentId}/overview` | [getAgentDetailOverview](../service-controlplane/internal/httpapi/agent_detail_handler.go#L93) |
| `GET` | `/api/v1/agents/{agentId}/runtime-inventory` | [getAgentRuntimeInventory](../service-controlplane/internal/httpapi/agent_detail_handler.go#L371) |
| `GET` | `/api/v1/agents/{agentId}/versions` | [listManagedAgentVersions](../service-controlplane/internal/httpapi/agent_catalog_handler.go#L602) |
| `GET` | `/api/v1/agents/{agentId}/versions/{version}` | [getManagedAgentVersion](../service-controlplane/internal/httpapi/agent_catalog_handler.go#L624) |
| `GET` | `/api/v1/agents/{agentId}/workspace-capabilities` | [agentWorkspaceCapabilities](../service-controlplane/internal/httpapi/workspace_definition.go#L56) |

### Workspaces, Skills, tools and marketplaces

| Method | Path | Handler / contract source |
| --- | --- | --- |
| `GET` | `/api/agents/{id}/bindings` | [listAgentBindings](../service-controlplane/internal/product/handlers_channels.go#L576) |
| `POST` | `/api/agents/{id}/bindings` | [addAgentBinding](../service-controlplane/internal/product/handlers_channels.go#L721) |
| `PUT` | `/api/agents/{id}/bindings` | [replaceAgentBindings](../service-controlplane/internal/product/handlers_channels.go#L643) |
| `DELETE` | `/api/agents/{id}/bindings/{index}` | [deleteAgentBinding](../service-controlplane/internal/product/handlers_channels.go#L836) |
| `PUT` | `/api/agents/{id}/bindings/{index}` | [updateAgentBinding](../service-controlplane/internal/product/handlers_channels.go#L778) |
| `POST` | `/api/agents/{id}/channels/{channelId}/default` | [setChannelDefault](../service-controlplane/internal/product/handlers_channels.go#L458) |
| `GET` | `/api/agents/{id}/presences` | [listAgentPresences](../service-controlplane/internal/product/handlers_presences.go#L75) |
| `POST` | `/api/agents/{id}/presences` | [createAgentPresence](../service-controlplane/internal/product/handlers_presences.go#L102) |
| `DELETE` | `/api/agents/{id}/presences/{channelId}` | [deleteAgentPresence](../service-controlplane/internal/product/handlers_presences.go#L221) |
| `GET` | `/api/agents/{id}/presences/{channelId}` | [getAgentPresence](../service-controlplane/internal/product/handlers_presences.go#L241) |
| `PUT` | `/api/agents/{id}/presences/{channelId}` | [updateAgentPresence](../service-controlplane/internal/product/handlers_presences.go#L156) |
| `GET` | `/api/agents/{id}/shares` | [listShares](../service-controlplane/internal/product/handlers_agent_extras.go#L539) |
| `POST` | `/api/agents/{id}/shares` | [addShare](../service-controlplane/internal/product/handlers_agent_extras.go#L573) |
| `DELETE` | `/api/agents/{id}/shares/{granteeType}/{granteeId}` | [revokeShare](../service-controlplane/internal/product/handlers_agent_extras.go#L607) |
| `GET` | `/api/agents/{id}/skills/workspace` | [listWorkspaceSkills](../service-controlplane/internal/product/handlers_agent_extras.go#L114) |
| `POST` | `/api/agents/{id}/skills/workspace/marketplace-install` | [marketplaceInstallSkill](../service-controlplane/internal/product/handlers_agent_extras.go#L300) |
| `DELETE` | `/api/agents/{id}/skills/workspace/{name}` | [deleteWorkspaceSkill](../service-controlplane/internal/product/handlers_agent_extras.go#L277) |
| `GET` | `/api/agents/{id}/skills/workspace/{name}` | [getWorkspaceSkill](../service-controlplane/internal/product/handlers_agent_extras.go#L168) |
| `PUT` | `/api/agents/{id}/skills/workspace/{name}` | [putWorkspaceSkill](../service-controlplane/internal/product/handlers_agent_extras.go#L234) |
| `GET` | `/api/agents/{id}/tools/active` | [toolsActive](../service-controlplane/internal/product/handlers_agent_extras.go#L342) |
| `GET` | `/api/agents/{id}/tools/catalog/builtins` | [toolsBuiltinCatalog](../service-controlplane/internal/product/handlers_agent_extras.go#L326) |
| `GET` | `/api/agents/{id}/tools/catalog/mcp-servers` | [toolsMcpCatalog](../service-controlplane/internal/product/handlers_agent_extras.go#L334) |
| `GET` | `/api/agents/{id}/workspace` | [workspaceSummary](../service-controlplane/internal/product/handlers_workspace.go#L102) |
| `DELETE` | `/api/agents/{id}/workspace/file` | [workspaceDeleteFile](../service-controlplane/internal/product/handlers_workspace.go#L403) |
| `GET` | `/api/agents/{id}/workspace/file` | [workspaceReadFile](../service-controlplane/internal/product/handlers_workspace.go#L265) |
| `POST` | `/api/agents/{id}/workspace/file` | [workspaceCreateFile](../service-controlplane/internal/product/handlers_workspace.go#L334) |
| `PUT` | `/api/agents/{id}/workspace/file` | [workspaceWriteFile](../service-controlplane/internal/product/handlers_workspace.go#L297) |
| `POST` | `/api/agents/{id}/workspace/file/move` | [workspaceMoveFile](../service-controlplane/internal/product/handlers_workspace.go#L367) |
| `GET` | `/api/agents/{id}/workspace/files` | [workspaceFiles](../service-controlplane/internal/product/handlers_workspace.go#L202) |
| `GET` | `/api/agents/{id}/workspace/subagents` | [listSubagents](../service-controlplane/internal/product/handlers_workspace.go#L462) |
| `POST` | `/api/agents/{id}/workspace/subagents/from-agent` | [subagentFromAgent](../service-controlplane/internal/product/handlers_workspace.go#L559) |
| `DELETE` | `/api/agents/{id}/workspace/subagents/{name}` | [deleteSubagent](../service-controlplane/internal/product/handlers_workspace.go#L597) |
| `PUT` | `/api/agents/{id}/workspace/subagents/{name}` | [upsertSubagent](../service-controlplane/internal/product/handlers_workspace.go#L515) |
| `POST` | `/api/agents/{id}/workspace/upload` | [workspaceUpload](../service-controlplane/internal/product/handlers_workspace.go#L422) |
| `GET` | `/api/marketplaces` | [listMarketplaces](../service-controlplane/internal/product/handlers_marketplaces.go#L78) |
| `POST` | `/api/marketplaces` | [createMarketplace](../service-controlplane/internal/product/handlers_marketplaces.go#L101) |
| `DELETE` | `/api/marketplaces/{id}` | [deleteMarketplace](../service-controlplane/internal/product/handlers_marketplaces.go#L140) |
| `GET` | `/api/marketplaces/{id}` | [getMarketplace](../service-controlplane/internal/product/handlers_marketplaces.go#L131) |
| `GET` | `/api/marketplaces/{id}/skills` | [browseMarketplaceSkills](../service-controlplane/internal/product/handlers_marketplaces.go#L152) |
| `GET` | `/api/toolsets/builtin` | [platformBuiltinCatalog](../service-controlplane/internal/product/handlers_workspaces.go#L360) |
| `GET` | `/api/toolsets/mcp-catalog` | [platformMcpCatalog](../service-controlplane/internal/product/handlers_workspaces.go#L364) |
| `GET` | `/api/workspaces` | [listWorkspaces](../service-controlplane/internal/product/handlers_workspaces.go#L157) |
| `POST` | `/api/workspaces` | [createWorkspace](../service-controlplane/internal/product/handlers_workspaces.go#L198) |
| `DELETE` | `/api/workspaces/{id}` | [deleteWorkspace](../service-controlplane/internal/product/handlers_workspaces.go#L336) |
| `GET` | `/api/workspaces/{id}` | [getWorkspace](../service-controlplane/internal/product/handlers_workspaces.go#L251) |
| `PATCH` | `/api/workspaces/{id}` | [patchWorkspace](../service-controlplane/internal/product/handlers_workspaces.go#L280) |
| `GET` | `/api/workspaces/{id}/agents` | [wsLinkedAgents](../service-controlplane/internal/product/workspace_publication.go#L198) |
| `DELETE` | `/api/workspaces/{id}/file` | [wsDeleteFile](../service-controlplane/internal/product/handlers_workspaces.go#L424) |
| `GET` | `/api/workspaces/{id}/file` | [wsReadFile](../service-controlplane/internal/product/handlers_workspaces.go#L383) |
| `PUT` | `/api/workspaces/{id}/file` | [wsWriteFile](../service-controlplane/internal/product/handlers_workspaces.go#L399) |
| `GET` | `/api/workspaces/{id}/files` | [wsFiles](../service-controlplane/internal/product/handlers_workspaces.go#L368) |
| `POST` | `/api/workspaces/{id}/publish` | [wsPublish](../service-controlplane/internal/product/workspace_publication.go#L158) |
| `GET` | `/api/workspaces/{id}/revisions` | [wsRevisions](../service-controlplane/internal/product/workspace_publication.go#L166) |
| `GET` | `/api/workspaces/{id}/skills` | [wsListSkills](../service-controlplane/internal/product/handlers_workspaces.go#L517) |
| `POST` | `/api/workspaces/{id}/skills/marketplace-install` | [wsMarketplaceInstall](../service-controlplane/internal/product/handlers_marketplaces.go#L167) |
| `DELETE` | `/api/workspaces/{id}/skills/{name}` | [wsDeleteSkill](../service-controlplane/internal/product/handlers_workspaces.go#L613) |
| `GET` | `/api/workspaces/{id}/skills/{name}` | [wsGetSkill](../service-controlplane/internal/product/handlers_workspaces.go#L482) |
| `PUT` | `/api/workspaces/{id}/skills/{name}` | [wsPutSkill](../service-controlplane/internal/product/handlers_workspaces.go#L553) |
| `GET` | `/api/workspaces/{id}/subagents` | [wsListSubagents](../service-controlplane/internal/product/handlers_workspaces.go#L759) |
| `DELETE` | `/api/workspaces/{id}/subagents/{name}` | [wsDeleteSubagent](../service-controlplane/internal/product/handlers_workspaces.go#L817) |
| `PUT` | `/api/workspaces/{id}/subagents/{name}` | [wsUpsertSubagent](../service-controlplane/internal/product/handlers_workspaces.go#L780) |
| `GET` | `/api/workspaces/{id}/tools` | [wsGetTools](../service-controlplane/internal/product/handlers_workspaces.go#L438) |
| `PUT` | `/api/workspaces/{id}/tools` | [wsPutTools](../service-controlplane/internal/product/handlers_workspaces.go#L451) |

### Environments

| Method | Path | Handler / contract source |
| --- | --- | --- |
| `GET` | `/api/environments` | [listEnvironments](../service-controlplane/internal/product/handlers_env.go#L82) |
| `POST` | `/api/environments` | [createEnvironment](../service-controlplane/internal/product/handlers_env.go#L118) |
| `DELETE` | `/api/environments/{id}` | [deleteEnvironment](../service-controlplane/internal/product/handlers_env.go#L221) |
| `GET` | `/api/environments/{id}` | [getEnvironment](../service-controlplane/internal/product/handlers_env.go#L157) |
| `PATCH` | `/api/environments/{id}` | [updateEnvironment](../service-controlplane/internal/product/handlers_env.go#L172) |
| `POST` | `/api/environments/{id}/archive` | [archiveEnvironment](../service-controlplane/internal/product/handlers_env.go#L236) |
| `POST` | `/api/environments/{id}/rotate-key` | [rotateEnvironmentKey](../service-controlplane/internal/product/handlers_env.go#L255) |

### Memory stores

| Method | Path | Handler / contract source |
| --- | --- | --- |
| `GET` | `/api/memory-stores` | [listMemoryStores](../service-controlplane/internal/product/handlers_memory.go#L64) |
| `POST` | `/api/memory-stores` | [createMemoryStore](../service-controlplane/internal/product/handlers_memory.go#L110) |
| `DELETE` | `/api/memory-stores/{id}` | [deleteMemoryStore](../service-controlplane/internal/product/handlers_memory.go#L224) |
| `GET` | `/api/memory-stores/{id}` | [getMemoryStore](../service-controlplane/internal/product/handlers_memory.go#L132) |
| `POST` | `/api/memory-stores/{id}/archive` | [archiveMemoryStore](../service-controlplane/internal/product/handlers_memory.go#L161) |
| `GET` | `/api/memory-stores/{id}/memories` | [listMemories](../service-controlplane/internal/product/handlers_memory.go#L248) |
| `DELETE` | `/api/memory-stores/{id}/memories/*path` | [deleteMemory](../service-controlplane/internal/product/handlers_memory.go#L382) |
| `GET` | `/api/memory-stores/{id}/memories/*path` | [readMemory](../service-controlplane/internal/product/handlers_memory.go#L46) |
| `PUT` | `/api/memory-stores/{id}/memories/*path` | [putMemory](../service-controlplane/internal/product/handlers_memory.go#L315) |
| `POST` | `/api/memory-stores/{id}/redact` | [redactMemory](../service-controlplane/internal/product/handlers_memory.go#L181) |

### Vault and OAuth connections

| Method | Path | Handler / contract source |
| --- | --- | --- |
| `GET` | `/api/vaults` | [listVaults](../service-controlplane/internal/product/handlers_vault.go#L65) |
| `POST` | `/api/vaults` | [createVault](../service-controlplane/internal/product/handlers_vault.go#L107) |
| `DELETE` | `/api/vaults/{id}` | [deleteVault](../service-controlplane/internal/product/handlers_vault.go#L217) |
| `GET` | `/api/vaults/{id}` | [getVault](../service-controlplane/internal/product/handlers_vault.go#L129) |
| `PATCH` | `/api/vaults/{id}` | [updateVault](../service-controlplane/internal/product/handlers_vault.go#L157) |
| `POST` | `/api/vaults/{id}/archive` | [archiveVault](../service-controlplane/internal/product/handlers_vault.go#L198) |
| `GET` | `/api/vaults/{id}/credentials` | [listCredentials](../service-controlplane/internal/product/handlers_vault.go#L241) |
| `POST` | `/api/vaults/{id}/credentials` | [addCredential](../service-controlplane/internal/product/handlers_vault.go#L270) |
| `DELETE` | `/api/vaults/{id}/credentials/{cid}` | [deleteCredential](../service-controlplane/internal/product/handlers_vault.go#L402) |
| `PATCH` | `/api/vaults/{id}/credentials/{cid}` | [updateCredential](../service-controlplane/internal/product/handlers_vault.go#L300) |
| `POST` | `/api/vaults/{id}/credentials/{cid}/validate` | [validateCredential](../service-controlplane/internal/product/handlers_vault.go#L357) |
| `GET` | `/api/vaults/{id}/oauth-connections` | [listOAuthConnections](../service-controlplane/internal/product/oauth_connector.go#L207) |
| `POST` | `/api/vaults/{id}/oauth-connections` | [createOAuthConnection](../service-controlplane/internal/product/oauth_connector.go#L245) |
| `PATCH` | `/api/vaults/{id}/oauth-connections/{connectionId}` | [updateOAuthConnection](../service-controlplane/internal/product/oauth_connector.go#L293) |
| `POST` | `/api/vaults/{id}/oauth-connections/{connectionId}/authorize` | [startOAuth](../service-controlplane/internal/product/oauth_connector.go#L388) |
| `POST` | `/api/vaults/{id}/oauth-connections/{connectionId}/disconnect` | [disconnectOAuth](../service-controlplane/internal/product/oauth_connector.go#L353) |
| `GET` | `/api/vaults/{id}/oauth-connections/{connectionId}/flows/{flowId}` | [oauthFlowStatus](../service-controlplane/internal/product/oauth_callback.go#L146) |
| `POST` | `/api/vaults/{id}/oauth-connections/{connectionId}/flows/{flowId}/cancel` | [cancelOAuth](../service-controlplane/internal/product/oauth_callback.go#L155) |
| `POST` | `/api/vaults/{id}/oauth-connections/{connectionId}/flows/{flowId}/complete` | [completeOAuth](../service-controlplane/internal/product/oauth_callback.go#L168) |
| `POST` | `/api/vaults/{id}/oauth-connections/{connectionId}/verify` | [verifyGitHubConnection](../service-controlplane/internal/product/oauth_github_verify.go#L42) |

### Channels, Deployments and work sources

| Method | Path | Handler / contract source |
| --- | --- | --- |
| `GET` | `/api/channels` | [listChannels](../service-controlplane/internal/product/handlers_channels.go#L176) |
| `POST` | `/api/channels` | [createChannel](../service-controlplane/internal/product/handlers_channels.go#L262) |
| `GET` | `/api/channels/types` | [listChannelTypes](../service-controlplane/internal/product/handlers_channels.go#L212) |
| `DELETE` | `/api/channels/{channelId}` | [deleteChannel](../service-controlplane/internal/product/handlers_channels.go#L415) |
| `GET` | `/api/channels/{channelId}` | [getChannel](../service-controlplane/internal/product/handlers_channels.go#L216) |
| `PUT` | `/api/channels/{channelId}` | [updateChannel](../service-controlplane/internal/product/handlers_channels.go#L339) |
| `GET` | `/api/channels/{channelId}/activity` | [channelWorkActivity](../service-controlplane/internal/product/channel_work_api.go#L383) |
| `GET` | `/api/channels/{channelId}/collaboration` | [channelWorkConfig](../service-controlplane/internal/product/channel_work_api.go#L83) |
| `PUT` | `/api/channels/{channelId}/collaboration` | [putChannelWorkConfig](../service-controlplane/internal/product/channel_work_api.go#L95) |
| `POST` | `/api/channels/{channelId}/deliveries/{deliveryId}/retry` | [retryChannelDelivery](../service-controlplane/internal/product/channel_work_api.go#L462) |
| `POST` | `/api/channels/{channelId}/disable` | [disableChannel](../service-controlplane/internal/product/handlers_channels.go#L437) |
| `POST` | `/api/channels/{channelId}/enable` | [enableChannel](../service-controlplane/internal/product/handlers_channels.go#L433) |
| `DELETE` | `/api/channels/{channelId}/identity` | [deleteChannelIdentity](../service-controlplane/internal/product/channel_work_api.go#L232) |
| `DELETE` | `/api/channels/{channelId}/links/{linkId}` | [unsubscribeChannelWork](../service-controlplane/internal/product/channel_work_api.go#L483) |
| `POST` | `/api/channels/{channelId}/messages/{messageId}/retry` | [retryChannelIntake](../service-controlplane/internal/product/channel_work_api.go#L547) |
| `POST` | `/api/channels/{channelId}/pairing` | [createChannelPairing](../service-controlplane/internal/product/channel_work_api.go#L209) |
| `GET` | `/api/deployments` | [listDeployments](../service-controlplane/internal/product/handlers_deploy.go#L122) |
| `POST` | `/api/deployments` | [createDeployment](../service-controlplane/internal/product/handlers_deploy.go#L157) |
| `POST` | `/api/deployments/webhook/{token}` | [triggerDeploymentWebhook](../service-controlplane/internal/product/handlers_deploy.go#L343) |
| `DELETE` | `/api/deployments/{id}` | [deleteDeployment](../service-controlplane/internal/product/handlers_deploy.go#L273) |
| `GET` | `/api/deployments/{id}` | [getDeployment](../service-controlplane/internal/product/handlers_deploy.go#L197) |
| `PATCH` | `/api/deployments/{id}` | [updateDeployment](../service-controlplane/internal/product/handlers_deploy.go#L206) |
| `POST` | `/api/deployments/{id}/archive` | [archiveDeployment](../service-controlplane/internal/product/handlers_deploy.go#L254) |
| `POST` | `/api/deployments/{id}/pause` | [pauseDeployment](../service-controlplane/internal/product/handlers_deploy.go#L306) |
| `POST` | `/api/deployments/{id}/run` | [runDeployment](../service-controlplane/internal/product/handlers_deploy.go#L288) |
| `POST` | `/api/deployments/{id}/unpause` | [unpauseDeployment](../service-controlplane/internal/product/handlers_deploy.go#L307) |
| `GET` | `/api/v1/work-sources` | [listWorkSources](../service-controlplane/internal/httpapi/work_source_handler.go#L61) |
| `POST` | `/api/v1/work-sources` | [createWorkSource](../service-controlplane/internal/httpapi/work_source_handler.go#L37) |
| `POST` | `/api/v1/work-sources/comment-outbox/flush` | [flushWorkSourceCommentOutbox](../service-controlplane/internal/httpapi/work_source_handler.go#L173) |
| `GET` | `/api/v1/work-sources/{workSourceId}` | [getWorkSource](../service-controlplane/internal/httpapi/work_source_handler.go#L79) |
| `PATCH` | `/api/v1/work-sources/{workSourceId}` | [patchWorkSource](../service-controlplane/internal/httpapi/work_source_handler.go#L91) |
| `POST` | `/api/v1/work-sources/{workSourceId}/reconcile` | [reconcileWorkSource](../service-controlplane/internal/httpapi/work_source_handler.go#L190) |
| `POST` | `/api/v1/work-sources/{workSourceId}/webhooks/github` | [githubWorkSourceWebhook](../service-controlplane/internal/httpapi/work_source_handler.go#L216) |
| `POST` | `/hooks/v1/automations/{automationId}/{triggerId}` | [automationWebhook](../service-controlplane/internal/httpapi/automation_handler.go#L492) |

### Teams, Issues, tasks, artifacts and approvals

| Method | Path | Handler / contract source |
| --- | --- | --- |
| `GET` | `/api/v1/agent-tasks` | [listAgentTasks](../service-controlplane/internal/httpapi/collaboration_handler.go#L1079) |
| `GET` | `/api/v1/agent-tasks/{taskId}` | [getAgentTask](../service-controlplane/internal/httpapi/collaboration_handler.go#L1109) |
| `POST` | `/api/v1/agent-tasks/{taskId}/cancel` | [cancelAgentTask](../service-controlplane/internal/httpapi/collaboration_handler.go#L1380) |
| `POST` | `/api/v1/agent-tasks/{taskId}/dispatch` | [dispatchAgentTask](../service-controlplane/internal/httpapi/collaboration_handler.go#L1222) |
| `POST` | `/api/v1/agent-tasks/{taskId}/inputs/replay` | [replayAgentTaskInputs](../service-controlplane/internal/httpapi/collaboration_handler.go#L1295) |
| `POST` | `/api/v1/agent-tasks/{taskId}/retry` | [retryAgentTask](../service-controlplane/internal/httpapi/collaboration_handler.go#L1390) |
| `GET` | `/api/v1/approvals` | [listApprovals](../service-controlplane/internal/httpapi/collaboration_handler.go#L2161) |
| `POST` | `/api/v1/approvals` | [createApproval](../service-controlplane/internal/httpapi/collaboration_handler.go#L2105) |
| `GET` | `/api/v1/approvals/{approvalId}` | [getApproval](../service-controlplane/internal/httpapi/collaboration_handler.go#L2183) |
| `POST` | `/api/v1/approvals/{approvalId}/decide` | [decideApproval](../service-controlplane/internal/httpapi/collaboration_handler.go#L2203) |
| `POST` | `/api/v1/artifacts/uploads` | [uploadArtifact](../service-controlplane/internal/httpapi/collaboration_handler.go#L1838) |
| `GET` | `/api/v1/artifacts/{artifactId}` | [getArtifact](../service-controlplane/internal/httpapi/collaboration_handler.go#L1998) |
| `POST` | `/api/v1/artifacts/{artifactId}/complete` | [completeArtifact](../service-controlplane/internal/httpapi/collaboration_handler.go#L2015) |
| `POST` | `/api/v1/artifacts/{artifactId}/download` | [downloadArtifact](../service-controlplane/internal/httpapi/collaboration_handler.go#L2045) |
| `GET` | `/api/v1/chat-agents` | [listChatAgents](../service-controlplane/internal/httpapi/chat_handler.go#L89) |
| `GET` | `/api/v1/chats` | [listChats](../service-controlplane/internal/httpapi/chat_handler.go#L72) |
| `POST` | `/api/v1/chats` | [createChat](../service-controlplane/internal/httpapi/chat_handler.go#L107) |
| `DELETE` | `/api/v1/chats/{chatId}` | [deleteChat](../service-controlplane/internal/httpapi/chat_handler.go#L246) |
| `GET` | `/api/v1/chats/{chatId}` | [getChat](../service-controlplane/internal/httpapi/chat_handler.go#L156) |
| `PATCH` | `/api/v1/chats/{chatId}` | [patchChat](../service-controlplane/internal/httpapi/chat_handler.go#L163) |
| `POST` | `/api/v1/chats/{chatId}/turns` | [sendChatTurn](../service-controlplane/internal/httpapi/chat_handler.go#L197) |
| `GET` | `/api/v1/events` | [collaborationEventStream](../service-controlplane/internal/httpapi/collaboration_handler.go#L47) |
| `GET` | `/api/v1/execution-attempts` | [listExecutionAttempts](../service-controlplane/internal/httpapi/orchestration_handler.go#L501) |
| `GET` | `/api/v1/execution-attempts/{attemptId}` | [getExecutionAttempt](../service-controlplane/internal/httpapi/orchestration_handler.go#L518) |
| `GET` | `/api/v1/inbox` | [listInbox](../service-controlplane/internal/httpapi/inbox_handler.go#L56) |
| `GET` | `/api/v1/inbox/summary` | [inboxSummary](../service-controlplane/internal/httpapi/inbox_handler.go#L77) |
| `GET` | `/api/v1/inbox/{inboxId}` | [getInbox](../service-controlplane/internal/httpapi/inbox_handler.go#L118) |
| `POST` | `/api/v1/inbox/{inboxId}/archive` | [archiveInbox](../service-controlplane/internal/httpapi/inbox_handler.go#L139) |
| `POST` | `/api/v1/inbox/{inboxId}/read` | [readInbox](../service-controlplane/internal/httpapi/inbox_handler.go#L125) |
| `GET` | `/api/v1/issues` | [listIssues](../service-controlplane/internal/httpapi/collaboration_handler.go#L237) |
| `POST` | `/api/v1/issues` | [createIssue](../service-controlplane/internal/httpapi/collaboration_handler.go#L135) |
| `GET` | `/api/v1/issues/{issueId}` | [getIssue](../service-controlplane/internal/httpapi/collaboration_handler.go#L288) |
| `PATCH` | `/api/v1/issues/{issueId}` | [updateIssue](../service-controlplane/internal/httpapi/collaboration_handler.go#L301) |
| `POST` | `/api/v1/issues/{issueId}/accept` | [acceptIssue](../service-controlplane/internal/httpapi/collaboration_handler.go#L443) |
| `PUT` | `/api/v1/issues/{issueId}/access` | [updateIssueAccess](../service-controlplane/internal/httpapi/namespace_access.go#L564) |
| `GET` | `/api/v1/issues/{issueId}/activity` | [listIssueActivities](../service-controlplane/internal/httpapi/collaboration_handler.go#L980) |
| `POST` | `/api/v1/issues/{issueId}/archive` | [archiveIssue](../service-controlplane/internal/httpapi/collaboration_handler.go#L509) |
| `GET` | `/api/v1/issues/{issueId}/artifacts` | [listIssueArtifacts](../service-controlplane/internal/httpapi/collaboration_handler.go#L994) |
| `POST` | `/api/v1/issues/{issueId}/assign` | [assignIssue](../service-controlplane/internal/httpapi/collaboration_handler.go#L694) |
| `POST` | `/api/v1/issues/{issueId}/children` | [createChildIssue](../service-controlplane/internal/httpapi/collaboration_handler.go#L381) |
| `GET` | `/api/v1/issues/{issueId}/comments` | [listIssueComments](../service-controlplane/internal/httpapi/collaboration_handler.go#L774) |
| `POST` | `/api/v1/issues/{issueId}/comments` | [addIssueComment](../service-controlplane/internal/httpapi/collaboration_handler.go#L745) |
| `POST` | `/api/v1/issues/{issueId}/comments/preview-routing` | [previewIssueCommentRouting](../service-controlplane/internal/httpapi/collaboration_handler.go#L961) |
| `DELETE` | `/api/v1/issues/{issueId}/comments/{commentId}` | [deleteIssueComment](../service-controlplane/internal/httpapi/collaboration_handler.go#L908) |
| `PATCH` | `/api/v1/issues/{issueId}/comments/{commentId}` | [updateIssueComment](../service-controlplane/internal/httpapi/collaboration_handler.go#L873) |
| `POST` | `/api/v1/issues/{issueId}/comments/{commentId}/resolve` | [resolveIssueComment](../service-controlplane/internal/httpapi/collaboration_handler.go#L935) |
| `GET` | `/api/v1/issues/{issueId}/export` | [exportIssue](../service-controlplane/internal/httpapi/collaboration_handler.go#L544) |
| `POST` | `/api/v1/issues/{issueId}/reject` | [rejectIssue](../service-controlplane/internal/httpapi/collaboration_handler.go#L447) |
| `POST` | `/api/v1/issues/{issueId}/reopen` | [reopenIssue](../service-controlplane/internal/httpapi/collaboration_handler.go#L451) |
| `GET` | `/api/v1/issues/{issueId}/subscribers` | [listIssueSubscribers](../service-controlplane/internal/httpapi/collaboration_handler.go#L1048) |
| `POST` | `/api/v1/issues/{issueId}/subscribers` | [subscribeIssue](../service-controlplane/internal/httpapi/collaboration_handler.go#L1013) |
| `DELETE` | `/api/v1/issues/{issueId}/subscribers/{subscriberType}/{subscriberRef}` | [unsubscribeIssue](../service-controlplane/internal/httpapi/collaboration_handler.go#L1061) |
| `GET` | `/api/v1/issues/{issueId}/summary` | [getIssueSummary](../service-controlplane/internal/httpapi/collaboration_handler.go#L526) |
| `POST` | `/api/v1/issues/{issueId}/team-proposals` | [createTeamProposal](../service-controlplane/internal/httpapi/team_composer_handler.go#L69) |
| `POST` | `/api/v1/issues/{issueId}/team-proposals/{proposalId}/confirm` | [confirmTeamProposal](../service-controlplane/internal/httpapi/team_composer_handler.go#L178) |
| `POST` | `/api/v1/issues/{issueId}/transition` | [transitionIssue](../service-controlplane/internal/httpapi/collaboration_handler.go#L410) |
| `GET` | `/api/v1/teams` | [listCollaborationTeams](../service-controlplane/internal/httpapi/collaboration_handler.go#L1629) |
| `POST` | `/api/v1/teams` | [createCollaborationTeam](../service-controlplane/internal/httpapi/collaboration_handler.go#L1555) |
| `POST` | `/api/v1/teams/from-proposal/{proposalId}` | [saveTeamProposalAsTeam](../service-controlplane/internal/httpapi/team_composer_handler.go#L264) |
| `GET` | `/api/v1/teams/{teamId}` | [getCollaborationTeam](../service-controlplane/internal/httpapi/collaboration_handler.go#L1654) |
| `PATCH` | `/api/v1/teams/{teamId}` | [updateCollaborationTeam](../service-controlplane/internal/httpapi/collaboration_handler.go#L1668) |
| `POST` | `/api/v1/teams/{teamId}/members` | [addCollaborationTeamMember](../service-controlplane/internal/httpapi/collaboration_handler.go#L1731) |
| `DELETE` | `/api/v1/teams/{teamId}/members/{memberId}` | [removeCollaborationTeamMember](../service-controlplane/internal/httpapi/collaboration_handler.go#L1822) |
| `PATCH` | `/api/v1/teams/{teamId}/members/{memberId}` | [updateCollaborationTeamMember](../service-controlplane/internal/httpapi/collaboration_handler.go#L1771) |
| `GET` | `/api/v1/teams/{teamId}/overview` | [getCollaborationTeamOverview](../service-controlplane/internal/httpapi/team_detail_handler.go#L59) |

### Workflow and Automation

| Method | Path | Handler / contract source |
| --- | --- | --- |
| `GET` | `/api/v1/automations` | [listAutomations](../service-controlplane/internal/httpapi/automation_handler.go#L146) |
| `POST` | `/api/v1/automations` | [createAutomation](../service-controlplane/internal/httpapi/automation_handler.go#L122) |
| `POST` | `/api/v1/automations/schedule-preview` | [previewAutomationSchedule](../service-controlplane/internal/httpapi/automation_handler.go#L373) |
| `DELETE` | `/api/v1/automations/{automationId}` | [archiveAutomation](../service-controlplane/internal/httpapi/automation_handler.go#L212) |
| `GET` | `/api/v1/automations/{automationId}` | [getAutomation](../service-controlplane/internal/httpapi/automation_handler.go#L171) |
| `PATCH` | `/api/v1/automations/{automationId}` | [updateAutomation](../service-controlplane/internal/httpapi/automation_handler.go#L184) |
| `GET` | `/api/v1/automations/{automationId}/deliveries` | [listAutomationDeliveries](../service-controlplane/internal/httpapi/automation_handler.go#L424) |
| `POST` | `/api/v1/automations/{automationId}/deliveries/{deliveryId}/replay` | [replayAutomationDelivery](../service-controlplane/internal/httpapi/automation_handler.go#L437) |
| `POST` | `/api/v1/automations/{automationId}/rotate-secret` | [rotateAutomationSecret](../service-controlplane/internal/httpapi/automation_handler.go#L389) |
| `GET` | `/api/v1/automations/{automationId}/runs` | [listAutomationRuns](../service-controlplane/internal/httpapi/automation_handler.go#L276) |
| `GET` | `/api/v1/automations/{automationId}/runs/{automationRunId}` | [getAutomationRun](../service-controlplane/internal/httpapi/automation_handler.go#L312) |
| `POST` | `/api/v1/automations/{automationId}/runs/{automationRunId}/cancel` | [cancelAutomationRun](../service-controlplane/internal/httpapi/automation_handler.go#L345) |
| `POST` | `/api/v1/automations/{automationId}/runs/{automationRunId}/rerun` | [rerunAutomationRun](../service-controlplane/internal/httpapi/automation_handler.go#L357) |
| `POST` | `/api/v1/automations/{automationId}/trigger` | [triggerAutomation](../service-controlplane/internal/httpapi/automation_handler.go#L251) |
| `GET` | `/api/v1/orchestration-definitions` | [listOrchestrationDefinitions](../service-controlplane/internal/httpapi/orchestration_handler.go#L65) |
| `POST` | `/api/v1/orchestration-definitions` | [createOrchestrationDefinition](../service-controlplane/internal/httpapi/orchestration_handler.go#L43) |
| `GET` | `/api/v1/orchestration-definitions/{definitionId}` | [getOrchestrationDefinition](../service-controlplane/internal/httpapi/orchestration_handler.go#L90) |
| `PATCH` | `/api/v1/orchestration-definitions/{definitionId}` | [patchOrchestrationDefinition](../service-controlplane/internal/httpapi/orchestration_handler.go#L102) |
| `POST` | `/api/v1/orchestration-definitions/{definitionId}/publish` | [publishOrchestrationDefinition](../service-controlplane/internal/httpapi/orchestration_handler.go#L184) |
| `GET` | `/api/v1/orchestration-definitions/{definitionId}/revisions` | [listOrchestrationRevisions](../service-controlplane/internal/httpapi/orchestration_handler.go#L205) |
| `POST` | `/api/v1/orchestration-definitions/{definitionId}/runs` | [startOrchestrationRun](../service-controlplane/internal/httpapi/orchestration_handler.go#L217) |
| `POST` | `/api/v1/orchestration-definitions/{definitionId}/validate` | [validateOrchestrationDefinition](../service-controlplane/internal/httpapi/orchestration_handler.go#L160) |
| `GET` | `/api/v1/orchestration-runs` | [listOrchestrationRuns](../service-controlplane/internal/httpapi/orchestration_handler.go#L268) |
| `GET` | `/api/v1/orchestration-runs/{runId}` | [getOrchestrationRun](../service-controlplane/internal/httpapi/orchestration_handler.go#L297) |
| `POST` | `/api/v1/orchestration-runs/{runId}/cancel` | [cancelOrchestrationRun](../service-controlplane/internal/httpapi/orchestration_handler.go#L341) |
| `GET` | `/api/v1/orchestration-runs/{runId}/events` | [listOrchestrationEvents](../service-controlplane/internal/httpapi/orchestration_handler.go#L322) |
| `GET` | `/api/v1/orchestration-runs/{runId}/graph` | [getOrchestrationGraph](../service-controlplane/internal/httpapi/orchestration_handler.go#L309) |
| `POST` | `/api/v1/orchestration-runs/{runId}/pause` | [pauseOrchestrationRun](../service-controlplane/internal/httpapi/orchestration_handler.go#L335) |
| `POST` | `/api/v1/orchestration-runs/{runId}/rerun` | [rerunOrchestrationRun](../service-controlplane/internal/httpapi/orchestration_handler.go#L344) |
| `POST` | `/api/v1/orchestration-runs/{runId}/resume` | [resumeOrchestrationRun](../service-controlplane/internal/httpapi/orchestration_handler.go#L338) |
| `POST` | `/api/v1/orchestration-runs/{runId}/signals/{name}` | [signalOrchestrationRun](../service-controlplane/internal/httpapi/orchestration_handler.go#L377) |

### Runtime profiles, pools and hosts

| Method | Path | Handler / contract source |
| --- | --- | --- |
| `GET` | `/api/v1/agent-instances` | [listAgentInstances](../service-controlplane/internal/httpapi/runtime_registry_handler.go#L27) |
| `GET` | `/api/v1/agent-instances/{instanceId}` | [getAgentInstance](../service-controlplane/internal/httpapi/runtime_registry_handler.go#L46) |
| `POST` | `/api/v1/runtime-bindings/{bindingId}/disable` | [disableRuntimeBinding](../service-controlplane/internal/httpapi/taskplane_handler.go#L403) |
| `POST` | `/api/v1/runtime-bindings/{bindingId}/enable` | [enableRuntimeBinding](../service-controlplane/internal/httpapi/taskplane_handler.go#L404) |
| `POST` | `/api/v1/runtime-host-enrollment-tokens` | [createRuntimeHostEnrollmentToken](../service-controlplane/internal/httpapi/taskplane_handler.go#L92) |
| `POST` | `/api/v1/runtime-host-enrollments` | [createRuntimeHostEnrollment](../service-controlplane/internal/httpapi/taskplane_handler.go#L52) |
| `GET` | `/api/v1/runtime-hosts` | [listRuntimeHosts](../service-controlplane/internal/httpapi/taskplane_handler.go#L329) |
| `GET` | `/api/v1/runtime-hosts/{hostId}` | [getRuntimeHost](../service-controlplane/internal/httpapi/taskplane_handler.go#L339) |
| `PATCH` | `/api/v1/runtime-hosts/{hostId}/capacity` | [updateRuntimeHostCapacity](../service-controlplane/internal/httpapi/taskplane_handler.go#L352) |
| `POST` | `/api/v1/runtime-hosts/{hostId}/drain` | [drainRuntimeHost](../service-controlplane/internal/httpapi/taskplane_handler.go#L378) |
| `POST` | `/api/v1/runtime-hosts/{hostId}/resume` | [resumeRuntimeHost](../service-controlplane/internal/httpapi/taskplane_handler.go#L381) |
| `GET` | `/api/v1/runtime-pools` | [listRuntimePools](../service-controlplane/internal/httpapi/runtime_registry_handler.go#L148) |
| `POST` | `/api/v1/runtime-pools` | [upsertRuntimePool](../service-controlplane/internal/httpapi/runtime_registry_handler.go#L122) |
| `GET` | `/api/v1/runtime-pools/{name}` | [getRuntimePool](../service-controlplane/internal/httpapi/runtime_registry_handler.go#L157) |
| `PUT` | `/api/v1/runtime-pools/{name}` | [upsertRuntimePool](../service-controlplane/internal/httpapi/runtime_registry_handler.go#L122) |
| `GET` | `/api/v1/runtime-profiles` | [listRuntimeProfiles](../service-controlplane/internal/httpapi/runtime_registry_handler.go#L95) |
| `POST` | `/api/v1/runtime-profiles` | [upsertRuntimeProfile](../service-controlplane/internal/httpapi/runtime_registry_handler.go#L69) |
| `GET` | `/api/v1/runtime-profiles/{name}` | [getRuntimeProfile](../service-controlplane/internal/httpapi/runtime_registry_handler.go#L104) |
| `PUT` | `/api/v1/runtime-profiles/{name}` | [upsertRuntimeProfile](../service-controlplane/internal/httpapi/runtime_registry_handler.go#L69) |

### Platform files

| Method | Path | Handler / contract source |
| --- | --- | --- |
| `GET` | `/api/files` | [listFiles](../service-controlplane/internal/product/handlers_files.go#L70) |
| `POST` | `/api/files` | [createFile](../service-controlplane/internal/product/handlers_files.go#L92) |
| `DELETE` | `/api/files/{id}` | [deleteFile](../service-controlplane/internal/product/handlers_files.go#L160) |
| `GET` | `/api/files/{id}` | [getFile](../service-controlplane/internal/product/handlers_files.go#L140) |
| `GET` | `/api/files/{id}/content` | [getFileContent](../service-controlplane/internal/product/handlers_files.go#L149) |

### Optional Kubernetes resources

| Method | Path | Handler / contract source |
| --- | --- | --- |
| `GET` | `/api/v1/mcpservers` | [listMCPServers](../service-controlplane/internal/httpapi/mcpserver_handler.go#L61) (Kubernetes client) |
| `POST` | `/api/v1/mcpservers` | [createMCPServer](../service-controlplane/internal/httpapi/mcpserver_handler.go#L29) (Kubernetes client) |
| `DELETE` | `/api/v1/mcpservers/{name}` | [deleteMCPServer](../service-controlplane/internal/httpapi/mcpserver_handler.go#L150) (Kubernetes client) |
| `GET` | `/api/v1/mcpservers/{name}` | [getMCPServer](../service-controlplane/internal/httpapi/mcpserver_handler.go#L98) (Kubernetes client) |
| `PATCH` | `/api/v1/mcpservers/{name}` | [patchMCPServer](../service-controlplane/internal/httpapi/mcpserver_handler.go#L115) (Kubernetes client) |
| `GET` | `/api/v1/mcpservers/{name}/tools` | [listMCPTools](../service-controlplane/internal/httpapi/mcpserver_handler.go#L172) (Kubernetes client) |
| `GET` | `/api/v1/modelconfigs` | [listModelConfigs](../service-controlplane/internal/httpapi/modelconfig_handler.go#L64) (Kubernetes client) |
| `POST` | `/api/v1/modelconfigs` | [createModelConfig](../service-controlplane/internal/httpapi/modelconfig_handler.go#L29) (Kubernetes client) |
| `DELETE` | `/api/v1/modelconfigs/{name}` | [deleteModelConfig](../service-controlplane/internal/httpapi/modelconfig_handler.go#L156) (Kubernetes client) |
| `GET` | `/api/v1/modelconfigs/{name}` | [getModelConfig](../service-controlplane/internal/httpapi/modelconfig_handler.go#L101) (Kubernetes client) |
| `PATCH` | `/api/v1/modelconfigs/{name}` | [patchModelConfig](../service-controlplane/internal/httpapi/modelconfig_handler.go#L118) (Kubernetes client) |
| `GET` | `/api/v1/sandboxes` | [listSandboxes](../service-controlplane/internal/httpapi/sandbox_handler.go#L78) (experimental + Kubernetes) |
| `POST` | `/api/v1/sandboxes` | [createSandbox](../service-controlplane/internal/httpapi/sandbox_handler.go#L29) (experimental + Kubernetes) |
| `DELETE` | `/api/v1/sandboxes/{name}` | [deleteSandbox](../service-controlplane/internal/httpapi/sandbox_handler.go#L111) (experimental + Kubernetes) |
| `GET` | `/api/v1/sandboxes/{name}` | [getSandbox](../service-controlplane/internal/httpapi/sandbox_handler.go#L94) (experimental + Kubernetes) |

### Health, version, fleet and operations

| Method | Path | Handler / contract source |
| --- | --- | --- |
| `GET` | `/api/v1/metrics/agents` | [queryAgentMetrics](../service-controlplane/internal/httpapi/overview_handler.go#L216) |
| `GET` | `/api/v1/metrics/tokens` | [queryTokenMetrics](../service-controlplane/internal/httpapi/overview_handler.go#L162) |
| `GET` | `/actuator/health` | [healthz](../service-controlplane/internal/httpapi/server.go#L1230) |
| `GET` | `/api/v1/commands` | [listRecentCommands](../service-controlplane/internal/httpapi/session_handler.go#L898) |
| `GET` | `/api/v1/dataplanes` | [listDataPlanes](../service-controlplane/internal/httpapi/dataplane_handler.go#L99) |
| `GET` | `/api/v1/dead-letters` | [listOutboxDeadLetters](../service-controlplane/internal/httpapi/taskplane_handler.go#L425) |
| `POST` | `/api/v1/dead-letters/{eventId}/replay` | [replayOutboxDeadLetter](../service-controlplane/internal/httpapi/taskplane_handler.go#L443) |
| `GET` | `/api/v1/overview` | [fleetOverview](../service-controlplane/internal/httpapi/overview_handler.go#L287) |
| `GET` | `/api/v1/overview/timeseries` | [overviewTimeseries](../service-controlplane/internal/httpapi/overview_handler.go#L437) |
| `GET` | `/api/v1/version` | [version](../service-controlplane/internal/httpapi/server.go#L1244) |
| `GET` | `/healthz` | [healthz](../service-controlplane/internal/httpapi/server.go#L1230) |
| `GET` | `/readyz` | [readyz](../service-controlplane/internal/httpapi/server.go#L1234) |

<a id="boundaries"></a>
## Machine protocols, compatibility, and unsupported features

### Machine and Task protocols

These endpoints serve registration clients, Runtime Hosts or Agents with execution-scoped Task tokens, not ordinary application Session clients. Task callbacks normally require `X-Agent-Task-Token`; Hosts use enrollment/runtime credentials and registration uses its own identity. Handlers verify persisted grants, Attempts and fences. Do not interchange user JWTs, Environment keys and internal tokens.

| Method | Path | Handler / contract source |
| --- | --- | --- |
| `POST` | `/api/v1/agent-registrations` | [registerExternalAgent](../service-controlplane/internal/httpapi/agent_catalog_handler.go#L107) |
| `POST` | `/api/v1/agent-runtime/exchange` | [registration](../service-controlplane/internal/httpapi/server.go#L444) |
| `POST` | `/api/v1/agent-tasks/{taskId}/ack` | [acknowledgeAgentTask](../service-controlplane/internal/httpapi/collaboration_handler.go#L1251) |
| `POST` | `/api/v1/agent-tasks/{taskId}/children` | [createAgentTaskChildIssue](../service-controlplane/internal/httpapi/collaboration_handler.go#L1434) |
| `POST` | `/api/v1/agent-tasks/{taskId}/complete` | [completeAgentTask](../service-controlplane/internal/httpapi/collaboration_handler.go#L1456) |
| `GET` | `/api/v1/agent-tasks/{taskId}/context` | [getAgentTaskContext](../service-controlplane/internal/httpapi/collaboration_handler.go#L1134) |
| `POST` | `/api/v1/agent-tasks/{taskId}/fail` | [failAgentTask](../service-controlplane/internal/httpapi/collaboration_handler.go#L1356) |
| `POST` | `/api/v1/agent-tasks/{taskId}/inputs/delivery-failed` | [failAgentTaskInputDelivery](../service-controlplane/internal/httpapi/collaboration_handler.go#L1272) |
| `POST` | `/api/v1/agent-tasks/{taskId}/progress` | [progressAgentTask](../service-controlplane/internal/httpapi/collaboration_handler.go#L1325) |
| `POST` | `/api/v1/agent-tasks/{taskId}/respond` | [respondAgentTask](../service-controlplane/internal/httpapi/collaboration_handler.go#L1404) |
| `GET` | `/api/v1/agent-tasks/{taskId}/run` | [getTaskRun](../service-controlplane/internal/httpapi/orchestration_handler.go#L575) |
| `GET` | `/api/v1/agent-tasks/{taskId}/run/artifacts` | [getTaskRunArtifacts](../service-controlplane/internal/httpapi/orchestration_handler.go#L690) |
| `GET` | `/api/v1/agent-tasks/{taskId}/run/graph` | [getTaskRunGraph](../service-controlplane/internal/httpapi/orchestration_handler.go#L588) |
| `POST` | `/api/v1/agent-tasks/{taskId}/run/node/complete` | [completeTaskRunNode](../service-controlplane/internal/httpapi/orchestration_handler.go#L601) |
| `POST` | `/api/v1/agent-tasks/{taskId}/run/node/fail` | [failTaskRunNode](../service-controlplane/internal/httpapi/orchestration_handler.go#L621) |
| `POST` | `/api/v1/agent-tasks/{taskId}/run/replan` | [replanTaskRun](../service-controlplane/internal/httpapi/orchestration_handler.go#L642) |
| `POST` | `/api/v1/agent-tasks/{taskId}/run/signals/{name}` | [signalTaskRun](../service-controlplane/internal/httpapi/orchestration_handler.go#L660) |
| `POST` | `/api/v1/agent-tasks/{taskId}/runtime-approvals` | [requestRuntimeToolApproval](../service-controlplane/internal/httpapi/managed_tool_approval.go#L82) |
| `POST` | `/api/v1/agent-tasks/{taskId}/runtime-approvals/{approvalId}/ack` | [acknowledgeRuntimeToolApprovalDecision](../service-controlplane/internal/httpapi/managed_tool_approval.go#L221) |
| `GET` | `/api/v1/agent-tasks/{taskId}/runtime-approvals/{approvalId}/decision` | [getRuntimeToolApprovalDecision](../service-controlplane/internal/httpapi/managed_tool_approval.go#L179) |
| `POST` | `/api/v1/agent-tasks/{taskId}/start` | [startAgentTask](../service-controlplane/internal/httpapi/collaboration_handler.go#L1315) |
| `POST` | `/api/v1/agent-tasks/{taskId}/workspace-application` | [reportWorkspaceApplication](../service-controlplane/internal/httpapi/workspace_definition.go#L151) |
| `POST` | `/api/v1/runtime-host-enrollments/exchange` | [exchangeRuntimeHostEnrollment](../service-controlplane/internal/httpapi/taskplane_handler.go#L126) |
| `POST` | `/api/v1/runtime-hosts/register` | [registerRuntimeHost](../service-controlplane/internal/httpapi/taskplane_handler.go#L293) |
| `POST` | `/api/v1/runtime-hosts/{hostId}/credentials` | [refreshRuntimeHostCredential](../service-controlplane/internal/httpapi/runtime_host_credentials.go#L28) |
| `POST` | `/api/v1/runtime-hosts/{hostId}/execution-attempts/claim` | [claimExecutionAttempt](../service-controlplane/internal/httpapi/taskplane_handler.go#L511) |
| `POST` | `/api/v1/runtime-hosts/{hostId}/execution-attempts/{attemptId}/cancelled` | [cancelledExecutionAttempt](../service-controlplane/internal/httpapi/taskplane_handler.go#L848) |
| `POST` | `/api/v1/runtime-hosts/{hostId}/execution-attempts/{attemptId}/checkpoint` | [checkpointExecutionAttempt](../service-controlplane/internal/httpapi/taskplane_handler.go#L722) |
| `POST` | `/api/v1/runtime-hosts/{hostId}/execution-attempts/{attemptId}/complete` | [completeExecutionAttempt](../service-controlplane/internal/httpapi/taskplane_handler.go#L699) |
| `POST` | `/api/v1/runtime-hosts/{hostId}/execution-attempts/{attemptId}/credentials` | [refreshExecutionCredentials](../service-controlplane/internal/httpapi/runtime_host_credentials.go#L71) |
| `POST` | `/api/v1/runtime-hosts/{hostId}/execution-attempts/{attemptId}/events` | [appendExecutionAttemptEvent](../service-controlplane/internal/httpapi/taskplane_handler.go#L745) |
| `POST` | `/api/v1/runtime-hosts/{hostId}/execution-attempts/{attemptId}/fail` | [failExecutionAttempt](../service-controlplane/internal/httpapi/taskplane_handler.go#L824) |
| `POST` | `/api/v1/runtime-hosts/{hostId}/execution-attempts/{attemptId}/observe` | [observeExecutionAttempt](../service-controlplane/internal/httpapi/taskplane_handler.go#L663) |
| `POST` | `/api/v1/runtime-hosts/{hostId}/execution-attempts/{attemptId}/physical-stop` | [confirmExecutionPhysicalStop](../service-controlplane/internal/httpapi/portal_native_stop.go#L46) |
| `POST` | `/api/v1/runtime-hosts/{hostId}/execution-attempts/{attemptId}/preparing` | [prepareExecutionAttempt](../service-controlplane/internal/httpapi/taskplane_handler.go#L650) |
| `POST` | `/api/v1/runtime-hosts/{hostId}/execution-attempts/{attemptId}/renew` | [renewExecutionAttempt](../service-controlplane/internal/httpapi/taskplane_handler.go#L620) |
| `POST` | `/api/v1/runtime-hosts/{hostId}/execution-attempts/{attemptId}/running` | [startExecutionAttempt](../service-controlplane/internal/httpapi/taskplane_handler.go#L680) |
| `POST` | `/api/v1/runtime-hosts/{hostId}/heartbeat` | [heartbeatRuntimeHost](../service-controlplane/internal/httpapi/taskplane_handler.go#L460) |
| `POST` | `/api/v1/runtime-hosts/{hostId}/state` | [setRuntimeHostState](../service-controlplane/internal/httpapi/taskplane_handler.go#L482) |
| `GET` | `/mcp/collaboration` | [registration](../service-controlplane/internal/httpapi/server.go#L643) |
| `POST` | `/mcp/collaboration` | [collaborationMCP](../service-controlplane/internal/httpapi/collaboration_mcp.go#L170) |

The Hands machine entry is `/v1/handss/environments/{environment_id}/tool-tasks/**`; the older `/api/environments/{environment_id}/tool-tasks/**` is forwarded through Session Portal. See [ManagedWorkerProtocol](../service-common/src/main/java/io/agentscope/builder/runtime/managed/ManagedWorkerProtocol.java) and the [Worker HTTP handler](../service-hands/src/main/java/io/agentscope/builder/hands/ManagedWorkerController.java) for claim/renew/output/result contracts. These are not client tool-result submission APIs.

Provider callbacks use provider-specific signatures/protocol credentials: `POST /api/channels/{dingtalk|feishu|wecom}/{channel_id}/callback`, `POST /api/channels/{github|gitlab}/{channel_id}/webhook`, and GET callback for WeCom. They mount when the integrated scheduler is configured; see [transport registration](../service-controlplane/internal/product/scheduler_runtime.go). Automation webhook: `POST /hooks/v1/automations/{automation_id}/{trigger_id}`; work-source GitHub webhook: `POST /api/v1/work-sources/{work_source_id}/webhooks/github`.

### Compatibility and private boundaries

`/api/v1/agent-sessions/**`, `/api/sessions/**`, `/api/v1/sessions/**` and native runtime paths have separate/older contracts; they are not aliases for `/v1/sessions`. New Managed Session writes use `/v1/sessions/{id}/events`, not old `POST .../turns`, `user-message`, `steer` or `inputs` paths. Gateway routes the old public Session prefix to Session Portal; actual availability depends on backend, authorization and migration state, not similarly named control-plane declarations.

The following older administration paths are for locating existing integrations, not the quickstart contract:

| Method | Path | Handler / contract source |
| --- | --- | --- |
| `GET` | `/api/sessions` | [listSessions](../service-controlplane/internal/product/handlers_sessions.go#L312) |
| `POST` | `/api/sessions` | [createSession](../service-controlplane/internal/product/handlers_sessions.go#L183) |
| `DELETE` | `/api/sessions/{id}` | [deleteSession](../service-controlplane/internal/product/handlers_sessions.go#L526) |
| `GET` | `/api/sessions/{id}` | [getSession](../service-controlplane/internal/product/handlers_sessions.go#L359) |
| `PATCH` | `/api/sessions/{id}` | [updateSession](../service-controlplane/internal/product/handlers_sessions.go#L368) |
| `POST` | `/api/sessions/{id}/archive` | [archiveSession](../service-controlplane/internal/product/handlers_sessions.go#L488) |
| `POST` | `/api/sessions/{id}/restore` | [restoreSession](../service-controlplane/internal/product/handlers_sessions.go#L507) |
| `GET` | `/api/v1/sessions` | [listSessions](../service-controlplane/internal/httpapi/overview_handler.go#L46) |
| `DELETE` | `/api/v1/sessions/{sessionId}` | [deleteSession](../service-controlplane/internal/httpapi/session_handler.go#L1012) |
| `GET` | `/api/v1/sessions/{sessionId}` | [getSession](../service-controlplane/internal/httpapi/overview_handler.go#L87) |
| `POST` | `/api/v1/sessions/{sessionId}/abort` | [abortSession](../service-controlplane/internal/httpapi/session_handler.go#L496) |
| `POST` | `/api/v1/sessions/{sessionId}/archive` | [archiveSession](../service-controlplane/internal/httpapi/session_handler.go#L502) |
| `GET` | `/api/v1/sessions/{sessionId}/commands` | [listSessionCommands](../service-controlplane/internal/httpapi/session_handler.go#L881) |
| `POST` | `/api/v1/sessions/{sessionId}/compress` | [compressSession](../service-controlplane/internal/httpapi/session_handler.go#L486) |
| `GET` | `/api/v1/sessions/{sessionId}/context` | [getSessionContext](../service-controlplane/internal/httpapi/session_handler.go#L83) |
| `GET` | `/api/v1/sessions/{sessionId}/events` | [getSessionEvents](../service-controlplane/internal/httpapi/session_handler.go#L331) |
| `GET` | `/api/v1/sessions/{sessionId}/events/stream` | [streamSessionEvents](../service-controlplane/internal/httpapi/session_handler.go#L399) |
| `GET` | `/api/v1/sessions/{sessionId}/messages` | [getSessionMessages](../service-controlplane/internal/httpapi/session_handler.go#L136) |
| `POST` | `/api/v1/sessions/{sessionId}/plan-mode` | [postSessionPlanMode](../service-controlplane/internal/httpapi/session_handler.go#L747) |
| `POST` | `/api/v1/sessions/{sessionId}/restore` | [restoreSession](../service-controlplane/internal/httpapi/session_handler.go#L536) |
| `GET` | `/api/v1/sessions/{sessionId}/subagent-tasks` | [getSessionSubagentTasks](../service-controlplane/internal/httpapi/session_handler.go#L701) |
| `DELETE` | `/api/v1/sessions/{sessionId}/subagent-tasks/{taskId}` | [cancelSessionSubagentTask](../service-controlplane/internal/httpapi/session_handler.go#L722) |
| `GET` | `/api/v1/sessions/{sessionId}/tasks` | [getSessionTasks](../service-controlplane/internal/httpapi/session_handler.go#L655) |
| `POST` | `/api/v1/sessions/{sessionId}/terminate` | [terminateSession](../service-controlplane/internal/httpapi/session_handler.go#L491) |
| `GET` | `/api/v1/sessions/{sessionId}/turns` | [listSessionTurns](../service-controlplane/internal/httpapi/session_handler.go#L469) |
| `POST` | `/api/v1/sessions/{sessionId}/user-message` | [postSessionUserMessage](../service-controlplane/internal/httpapi/session_handler.go#L775) |

`/api/internal/**` returns 404 at the public Gateway; components still authenticate service-to-service calls independently. Feature-gated `/api/v1/dp/**` is an older internal coordination protocol, not a user API. Older dataplane/Worker observation paths are outside the recommended integration surface. Historical declarations are in the [ownership inventory](session-portal-api-inventory.md), [Java CSV](session-portal-java-routes.csv) and [Go CSV](session-portal-go-route-declarations.csv); those inventories do not replace current entry/capability checks.

### Not supported by the current Session protocol

- Session list/update and archive/restore/delete lifecycle operations.
- Public Files, Artifacts, Budget, Usage subresources, Checkpoints, restore/fork, Webhooks, snapshots/resource snapshots, trace/export.
- Threads, client tool confirmation/external results, image/file input, preview/deltas, SSE resume or durable delivery.
- Nonempty Skills, multiagent configuration or unverified execution identities in this first Session protocol.

Managing a related control-plane resource does not enable these Session APIs. A Controller, stored schema or SDK method does not override the entry filter. Use capabilities and the supported endpoints above for your conversation flow.

<a id="implementation-sources"></a>
## Implementation sources and maintenance

When changing routes, verify registration, Gateway, security filters, handlers and capabilities together. Keep English/Chinese references and quickstarts aligned when public contracts change. The following sources define the current protocol:

- [Session controller](../service-session-portal/src/main/java/io/agentscope/builder/portal/api/SessionApiController.java)
- [Session entry filter and errors](../service-session-portal/src/main/java/io/agentscope/builder/portal/config/SessionApiErrors.java)
- [Session creation and representation](../service-session-portal/src/main/java/io/agentscope/builder/portal/session/SessionApi.java)
- [Event validation, reads and SSE](../service-session-portal/src/main/java/io/agentscope/builder/portal/session/ManagedSessionTurnsApi.java)
- [Durable protocol and projection](../service-common/src/main/java/io/agentscope/builder/runtime/managed/ManagedSessionProtocol.java)
- [Go route registration](../service-controlplane/internal/httpapi/server.go)
- [Product registration](../service-controlplane/internal/product/server.go)
- [Gateway](../service-gateway/src/main/resources/application.yml)
- [Protocol verification](../tests/session-protocol/README.md)
