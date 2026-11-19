---
title: Agent Protocol
zh_link: /dev/zh/integration/protocol/agent-protocol
---

`agentscope-extensions-agent-protocol` exposes AgentScope's [Harness Agent](/dev/en/docs/harness/architecture) as a standard [Agent Protocol](https://agentprotocol.ai/) HTTP API, letting external systems (CI, other agent platforms, automation jobs) submit "tasks" using a uniform contract — no need to know the implementation details.

## When to use

- You want the Agent to be remotely scheduled like a cloud function.
- An existing team uses an Agent Protocol client and you'd like to plug in directly.
- You're embedding a Harness Agent in a Spring Boot service and want auto-exposed `/tasks` REST endpoints.
- You're hosting a [remote subagent](/dev/en/docs/harness/subagent#remote-subagent) that another Harness parent calls over HTTP.

## Protocol layering

AgentScope uses different protocols for different trust / UX boundaries:

| Layer | Role |
| --- | --- |
| **AG-UI** | User-facing chat UI event stream (browser ↔ app) |
| **Agent Protocol** | Internal remote-subagent / task HTTP API (parent harness ↔ remote agent service) |
| **A2A** | External agent-to-agent interop (separate extension; not part of this remote-subagent streaming/HITL work) |

## Add the dependency

```xml
<dependency>
    <groupId>io.agentscope</groupId>
    <artifactId>agentscope-extensions-agent-protocol</artifactId>
    <version>${agentscope.version}</version>
</dependency>
```

## Enable

The module is delivered as a Spring Boot auto-configuration. In a Spring Boot app:

1. Provide a `HarnessAgent` bean (or a custom `AgentFactory`).
2. Enable in `application.yml`:

```yaml
agentscope:
  agent-protocol:
    enabled: true
    # optional — control-plane TaskRecord directory (not an execution agent's workspace)
    # task-store-path: ${user.dir}/.agentscope/agent-protocol
```

The `/tasks` REST endpoints are then registered automatically.

### Control-plane vs execution workspace

`AgentProtocolTaskStore` persists protocol task metadata (`TaskRecord` for submit / resume /
snapshot) through a dedicated `ProtocolTaskRepository`. By default that repository is rooted at
`agentscope.agent-protocol.task-store-path` (`${user.dir}/.agentscope/agent-protocol`), under the
synthetic bucket `agents/_agentscope_protocol/tasks/`.

This path is **independent** of each `HarnessAgent`'s own `WorkspaceManager` (MEMORY, sessions,
skills). Multi-agent factories may give each agent a different `.workspace(...)`; protocol
`task_id` lookup always goes through the control-plane repository.

You may supply your own `ProtocolTaskRepository` bean to override the default. Construct
`AgentProtocolTaskStore` with a `ProtocolTaskRepository` only — do not pass an execution agent's
`WorkspaceManager`.

## Concurrent execution

This adapter uses a Spring-managed `HarnessAgent` Bean whose lifetime covers protocol execution and recovery, ending at application shutdown. Each task supplies its identity through `RuntimeContext`. For ordinary web handlers, use a shared Builder and a new Agent per request; see [Instance lifecycle](/dev/en/docs/building-blocks/agent#instance-lifecycle). Do not close the adapter-owned Agent when task submission returns:

```java
@Bean
public HarnessAgent harnessAgent() {
    return HarnessAgent.builder()
            .name("protocol-agent")
            .model("dashscope:qwen-plus")
            .build();
}
```

Concurrent requests for the same session are automatically serialized; different sessions run in parallel.
A submission that targets a session with a live turn is rejected with HTTP 409 and the body reports the
busy `session_id`, so the caller waits for that turn instead of starting a second writer.

## Agent selection (`AgentFactory`)

Which agent runs a task is decided by an `AgentFactory` bean. Without one, the default factory returns the single `HarnessAgent` bean for every task.

Define your own bean to route by `agent_id`, tenant, or any custom submission-context key:

```java
@Bean
AgentFactory agentFactory(Map<String, HarnessAgent> agentsByName) {
    return request -> {
        String tenant = request.contextString("tenant");
        log.info("task {} agent_id={} tenant={} resume={}",
                request.taskId(), request.agentId(), tenant, request.resume());
        return agentsByName.getOrDefault(request.agentId(), agentsByName.get("default"));
    };
}
```

`AgentRequest` fields:

| Field | Notes |
| --- | --- |
| `taskId()` | Task identifier for this turn; the conversation is `sessionId()` |
| `agentId()` | Requested `agent_id` from the submission |
| `input()` | User input; empty on a resume run |
| `userId()` / `parentSessionId()` | Parsed from `context.user_id` / `context.parent_session_id` |
| `sessionId()` | Session the run executes in: `context.session_id` when supplied, otherwise `taskId()` |
| `resume()` | `true` when re-running a task that was awaiting tool confirmation |
| `context()` | The submission `context` map exactly as received, including custom keys; `contextValue(key)` / `contextString(key)` are convenience accessors |
| `attributes()` | Just the `context.attributes` map; `attributeString(key)` is a convenience accessor |

The factory is invoked once per run — on submit and again on every `/resume` — with the original submission context, so routing decisions stay stable across HITL pauses.

Return a distinct instance per call (for example a prototype-scoped bean) when tasks run concurrently. Returning `null` fails the task with an error status.

## Context attributes

Callers pass their own data in `context.attributes`, nested so it never mixes with the protocol's own context fields. Besides being visible to the `AgentFactory`, attributes reach the running agent through its `RuntimeContext`.

They arrive as **one map under a single namespaced key**, `AgentProtocolConstants.RUNTIME_CONTEXT_ATTRIBUTES_KEY` (`agentprotocol.context.attributes`):

```java
Map<String, Object> attributes = ctx.get(AgentProtocolConstants.RUNTIME_CONTEXT_ATTRIBUTES_KEY);
String tenant = attributes != null ? (String) attributes.get("tenant") : null;
```

Attributes are namespaced rather than written as top-level keys because the framework itself reads a few plain runtime-context keys — `agentId` drives async tool wakeup routing, `outboundAddress` carries the gateway reply address. A caller naming an attribute after one of those would otherwise change how the agent behaves. Attributes are never rendered into the system prompt, so they do not affect what the model sees.

### Promoting attributes to their own keys

Register `RuntimeContextCustomizer` beans when a tool expects a plain key such as `ctx.get("tenant")`, or to turn attributes into typed values. `RuntimeContextCustomizer.flatten` copies an explicit allow-list, silently skipping framework-reserved names:

```java
@Bean
RuntimeContextCustomizer promoteTenantKeys() {
    return RuntimeContextCustomizer.flatten("tenant", "ticket_id");
}

@Bean
RuntimeContextCustomizer tenantContext(TenantService tenants) {
    return (request, builder) -> {
        String tenant = request.attributeString("tenant");
        if (tenant != null) {
            builder.put(TenantInfo.class, tenants.load(tenant));
        }
    };
}
```

Every customizer bean is applied to every run, in `@Order`, after the namespaced injection — a later customizer overrides an earlier one. Hand-written customizers are trusted and may write any key, including reserved ones.

### Sending attributes from a parent agent

A parent agent delegating to a remote subagent supplies attributes in two ways, merged with the per-call ones winning:

```java
// Static, per subagent
SubagentDeclaration.builder()
        .name("researcher")
        .description("Remote researcher")
        .url("http://remote:8080")
        .remoteContextAttributes(Map.of("region", "cn"))
        .build();

// Per call, on the parent's RuntimeContext
RuntimeContext.builder()
        .sessionId("sess-1")
        .put(AgentSpawnTool.CTX_REMOTE_CONTEXT_ATTRIBUTES, Map.of("tenant", "acme"))
        .build();
```

Values must be JSON-serializable.

## Endpoints

### Submit a task

`POST /tasks`

```json
{
  "task_id": "task_123",
  "agent_id": "researcher",
  "input": "Summarize the latest release notes",
  "context": {
    "user_id": "u-1",
    "parent_session_id": "sess-parent",
    "stream": true,
    "detail": "full",
    "deny_rules": [
      {
        "tool_name": "bash",
        "behavior": "DENY",
        "source": "parent"
      }
    ],
    "attributes": {
      "tenant": "acme",
      "ticket_id": "INC-1001"
    }
  }
}
```

Optional `context` fields:

| Field | Notes |
| --- | --- |
| `user_id` | Forwarded into the remote agent's `RuntimeContext` |
| `parent_session_id` | Parent session identity (for tracing / correlation) |
| `parent_agent_id` | Submitting agent's id — the caller's journal namespace (`agentId ?? name` on a `HarnessAgent`), so an agent configured by name reports that name; recorded on the task record when `agentscope.agent-protocol.use-caller-parent-identity` is enabled |
| `session_id` | Conversation this submission runs in. Repeat the same value for every follow-up turn — see [Continue an existing session](#continue-an-existing-session) |
| `stream` | Whether the caller intends to consume SSE events |
| `detail` | `status` (default), `full` or `verbose` — see [Stream detail levels](#stream-detail-levels) |
| `deny_rules` | Parent DENY permission rules to enforce on the remote side |
| `attributes` | Caller-defined key/values for routing and for the run's `RuntimeContext`; see [Context attributes](#context-attributes) |

Response on success: `{ "task_id", "status": "pending" }`.

### Continue an existing session

A submission runs in `context.session_id` when the caller supplies one, so a caller can choose between
an isolated task and a further turn of the same conversation:

| Goal | Submission | Agent session |
| --- | --- | --- |
| Dispatch an unrelated task | new `task_id`, no `session_id` | new session, named after the new `task_id` |
| Continue a conversation | new `task_id` + the same `"session_id": "sub-7f3a"` | `sub-7f3a` |

```json
{
  "task_id": "task_456",
  "agent_id": "researcher",
  "input": "Now expand the security section",
  "context": {
    "session_id": "sub-7f3a"
  }
}
```

The new task keeps its own record, result and event stream — `/tasks/task_456` reports only this
turn's reply — while the remote agent loads the stored conversation and treats `input` as the next
user turn. The session is named by the caller and never derived from a task, so continuing does not
require remembering a previous task id.

Four rules apply:

- **First use registers the owner pair.** The server records `(user_id, agent_id)` on first use and
  verifies it afterwards; naming an existing session with a different `user_id` or `agent_id` is
  rejected with HTTP 400 instead of silently opening an empty conversation elsewhere. A `user_id` is
  therefore fixed once registered. This pair is the only ownership check; `created_by` (the
  submitting agent id) is stored for diagnostics and is not a security boundary. In a multi-tenant
  deployment, `user_id` must come from a trusted authentication context at the ingress layer — the
  registry trusts the value it receives.
- **One running turn per session.** A submission for a session with a live turn is rejected with
  HTTP 409 and the body carries `session_id`. The guard is per session because two task ids can share
  one.
- **The task id stays per turn.** Reusing a `task_id` reuses its record, so ids must be unique per
  submission.
- **Reachability.** The session is stored through the configured state store, so a continuation can
  be served by another instance only when that store — and the session registry — are shared.

Without `session_id` the session is the submitted `task_id`, which keeps every submission isolated.

From the harness side, `agent_spawn` generates the child session once and stores it on the child
entry, so `agent_send` with that child's `agent_key` or `label` repeats the same `session_id` whether
the child runs locally or remotely. `agent_send` has no `task_id` route: subagents are only created by
`agent_spawn`.

### Poll / wait / cancel

| Method | Path | Notes |
| --- | --- | --- |
| `GET` | `/tasks/{taskId}` | Snapshot (`task_id`, `session_id`, `status`, result, error, `pending_confirms`) |
| `GET` | `/tasks/{taskId}/wait?timeout_seconds=7200` | Block until terminal (or `awaiting_confirm`) |
| `POST` | `/tasks/{taskId}/cancel` | Request cancellation |

While the remote agent waits for tool confirmation, the snapshot reports `status: awaiting_confirm` together with `confirm_expires_at`, while the stored `TaskStatus` remains `RUNNING` so parent barriers keep waiting.

**A paused turn owns its session.** The confirm decisions were produced against the state it paused in, so letting a new turn advance that state first would apply them to a conversation that moved on. A submission naming the same `session_id` therefore returns `409`:

```json
{
  "error": "session conv-1 has a turn awaiting confirmation (task t-1); resume or cancel it before submitting again",
  "status": "awaiting_confirm",
  "session_id": "conv-1",
  "pending_task_id": "t-1",
  "confirm_expires_at": "2026-11-20T18:00:00Z"
}
```

Resume or cancel that task to free the session; both paths release it. Other sessions are unaffected.

A `409` carries a `status` that distinguishes two conflicts, and they need different actions:

| `status` | Meaning | Fields | What the caller must do |
| --- | --- | --- | --- |
| `awaiting_confirm` | The previous turn is paused for a tool-confirmation decision and will not finish on its own | `session_id`, `pending_task_id`, `confirm_expires_at` | `/resume` that task with decisions, or `/cancel` it. Waiting with `task_output` does not help. |
| `session_busy` | The previous turn is still running | `session_id` | Wait for that turn with `task_output` / `wait_async_results`, then submit again. |

A `409` without a `status` (for example from an older server, or a generic task conflict) is treated as a busy session. From the harness side, `agent_send` reports these as `status: awaiting_confirm` and `status: still_running` respectively; neither echoes the child session in the result text.

A pause is written before the run hands its leases back, so a new submission always gets exactly one
answer: `session_busy` while the lease is still held, `awaiting_confirm` once it is released.
Accordingly, a `/resume` that arrives while the previous run is still unwinding waits for it briefly
(at most `resume-lease-wait-ms`, two seconds by default) instead of failing.

**The wait is bounded.** A turn still paused after `confirm-timeout-ms` (one day by default, configurable to any length — no upper bound is applied, only values below one second are raised to one second) is settled as `FAILED` with `confirm_timeout: no /resume within <n> ms`, its event stream is closed and the session is released. Settlement happens on the next snapshot, wait or submission — no background task is involved. The same read-side repair settles a task left `running` or `pending` by an instance that died with no live lease. Consequently a task nobody queries stays non-terminal until it is read; if a deployment requires every task to converge to a terminal state on its own, an external sweep that GETs outstanding tasks is needed.

### Stream events (SSE)

`GET /tasks/{taskId}/events`

Server-Sent Events of agent progress. Requires `agentscope.agent-protocol.streaming-enabled=true` (default).

Reconnect / resume:

- Query param `from_seq` — start after this sequence number
- Header `Last-Event-ID` — used when `from_seq` is omitted (same meaning)

Each SSE message uses the event seq as `id`, the remote event type as `event`, and a JSON body as `data`.

#### Stream detail levels

`context.detail` on submission decides how much of the run reaches subscribers. Each level is a superset of the previous one:

| `detail` | Event types on the stream |
|----------|---------------------------|
| `status` (default) | `RUN_STARTED`, `RUN_FINISHED`, `RUN_ERROR`, `TOOL_CALL_START`, `TOOL_CALL_END`, `TOOL_RESULT`, `REQUIRE_CONFIRM`, `STATUS` |
| `full` | plus `TEXT_DELTA`, `THINKING_DELTA` |
| `verbose` | plus `AGENT_EVENT` — every remaining agent event, including block boundaries, tool argument and tool output deltas, model calls with token usage, agent results and custom events |

Only `verbose` reproduces the agent's own event stream in full. An unrecognized value is treated as `status`.

Every event body also carries two fields beyond its type-specific ones:

| Field | Meaning |
|-------|---------|
| `eventType` | Name of the source `AgentEventType`, e.g. `MODEL_CALL_END`. Lets a client filter or log without parsing `payload` |
| `payload` | The source `AgentEvent` serialized in full. Restores the original event with its id, timestamp and metadata intact, and is the only representation of an `AGENT_EVENT` |

Both are additive: a client that ignores them keeps reading the flat fields (`text`, `toolCallId`, `status`, …) exactly as before, and one that predates `AGENT_EVENT` simply skips those messages.

### Resume after HITL

`POST /tasks/{taskId}/resume`

```json
{
  "decisions": [
    { "toolCallId": "call-1", "approved": true },
    { "toolCallId": "call-2", "approved": false, "reason": "not allowed in production" }
  ]
}
```

`tool_call_id` is also accepted as an alias for `toolCallId`. Requires `agentscope.agent-protocol.hitl-enabled=true` (default). On success returns `{ "task_id", "status": "running" }`.

How remote HITL interacts with a calling parent harness is documented under [Remote authorization](/dev/en/docs/harness/subagent#remote-authorization).

## Configuration

| Property | Type | Default | Notes |
| --- | --- | --- | --- |
| `agentscope.agent-protocol.enabled` | boolean | `false` | Whether to register the `/tasks` REST endpoints |
| `agentscope.agent-protocol.streaming-enabled` | boolean | `true` | Expose SSE `GET /tasks/{id}/events` |
| `agentscope.agent-protocol.hitl-enabled` | boolean | `true` | Pause tasks for tool confirmation and accept `/resume` |
| `agentscope.agent-protocol.confirm-timeout-ms` | long | `86400000` | How long a confirmation pause may last, one day by default; any value is accepted (no upper bound, one-second floor); an expired turn is settled as failed and releases its session |
| `agentscope.agent-protocol.sse-replay-buffer-size` | int | `256` | Per-task replay buffer for late SSE subscribers |
| `agentscope.agent-protocol.sse-timeout-ms` | long | `10800000` | Max SSE subscription duration (ms) |
| `agentscope.agent-protocol.task-store-path` | String | `${user.dir}/.agentscope/agent-protocol` | Control-plane directory for task records, independent of any execution workspace |
| `agentscope.agent-protocol.run-lease-ttl-ms` | long | `60000` | Run lease TTL; an expired lease without an owner settles the task as failed |
| `agentscope.agent-protocol.run-heartbeat-interval-ms` | long | `20000` | Lease renewal period, clamped to at most half the TTL |
| `agentscope.agent-protocol.cancel-check-interval-ms` | long | `500` | How often a running task checks for a cancellation intent; raise it when the registry is shared (one read per interval per running task) |
| `agentscope.agent-protocol.resume-lease-wait-ms` | long | `2000` | How long `/resume` waits for a paused run to hand its leases back; clamped to 100–30000 ms. Raise it when a shared registry makes the handback slower |
| `agentscope.agent-protocol.use-caller-parent-identity` | boolean | `false` | Record the caller's `parent_agent_id` / `parent_session_id` instead of the control-plane namespace |

Example:

```yaml
agentscope:
  agent-protocol:
    enabled: true
    streaming-enabled: true
    hitl-enabled: true
    sse-replay-buffer-size: 256
    sse-timeout-ms: 10800000
    run-lease-ttl-ms: 60000
    run-heartbeat-interval-ms: 20000
    confirm-timeout-ms: 86400000
    cancel-check-interval-ms: 500
    resume-lease-wait-ms: 2000
    use-caller-parent-identity: false
```

> When `enabled` is `false` (the default) the dependency stays inert — no REST endpoints are exposed, safe to ship.

## Distributed deployment

Submission, `/resume`, `/cancel` and the `/wait` poll may reach different instances. Three
collaborators default to in-process implementations and must be replaced with shared ones when they
do:

| Collaborator | Why it must be shared |
| --- | --- |
| `ProtocolTaskRunRegistry` | Carries the run lease (idempotent submit, owner-lost settlement) and the one-writer guard on a session |
| `ProtocolTaskContextStore` | Submission metadata re-read by `/resume`, which may be served elsewhere |
| `ProtocolSessionRegistry` | Session ownership verified on every submission that names a `session_id` |

A shared `ProtocolTaskRepository` and `AgentProtocolEventBus` cover record and event visibility.

Missing one does not fail startup: single-instance behaviour is preserved silently, and the symptom
is a duplicate run or an unverified session claim once a second instance is added.

### Wiring all three to one shared store

With a `BaseStore` implementation that supports atomic compare-and-set (Redis, JDBC, MySQL,
PostgreSQL, MongoDB) a single bean switches all three collaborators above:

```java
@Bean
ProtocolStores protocolStores(BaseStore protocolStore) {
    return ProtocolStores.on(protocolStore);
}
```

Leases record their holder as an `ownerId` formatted `<host>:<pid>:<random>` and derived by the
implementation — instance identity is not a deployment decision, and it must differ per instance. It
is diagnostic only (ownership is decided by the per-acquisition `leaseId`), but a lease read from a
shared store then names the machine holding it.

Cancellation is a written intent plus polling, not a broadcast: any instance that receives `/cancel`
stores an intent, and the holder reads it every `cancel-check-interval-ms`. With a shared store that
is one read per interval per running task — 500ms by default means 2 QPS per running task; raise it
(for example to 2–5 seconds) when running many concurrent tasks across instances.

Each collaborator keeps its own namespace under `agent-protocol`:

| Data | Namespace |
| --- | --- |
| Run leases | `agent-protocol/task-run-registry-leases` |
| Cancel intents | `agent-protocol/task-run-registry-cancels` |
| Submission metadata | `agent-protocol/task-context-store` |
| Session ownership | `agent-protocol/session-registry` |

Three prerequisites:

- **Keep instance clocks in sync.** A lease stores an absolute `expires_at` (epoch millis) produced
  by the acquiring instance's `Clock.systemUTC()`, and every instance compares it against its own
  clock. The shared store only holds the number — it does not decide expiry and has no TTL or
  server-time primitive of its own in `BaseStore`. A fast instance can therefore consider a slow
  instance's lease expired and take it over. Keep clock skew well below half the lease TTL (10s by
  default with `run-lease-ttl-ms=60000` and `run-heartbeat-interval-ms=20000`); NTP is sufficient
  for typical drift.
- **Pass the protocol control plane's own store instance.** An implementation that partitions by
  agent includes the agent identifier in its unique key, so reusing an execution agent's instance
  files control-plane state under that agent; construct one with a dedicated identity (for example
  `_agentscope_protocol`).
- **The store must support atomic compare-and-set.** `ProtocolStores.on(store)` checks
  `BaseStore.supportsAtomicJournalStorage()` and fails fast with an `IllegalStateException` when it
  is `false`. Object-storage implementations (OSS, COS) cannot fence lease acquisitions — their
  `putIfVersion` is a read-then-write sequence — so wiring them would let two instances own the same
  task. Use Redis, JDBC, MySQL, PostgreSQL or MongoDB. A single-instance deployment does not need
  this bean at all: without it the auto-configuration supplies process-local in-memory
  collaborators.

A collaborator bean declared explicitly (`ProtocolTaskRunRegistry`, ...) takes precedence over
`ProtocolStores`.

## Workspace integration

Each task receives an isolated workspace from `WorkspaceManager`. Once the task finishes, files and logs in the workspace are exposed via standard Agent Protocol endpoints so external clients can fetch artifacts.
