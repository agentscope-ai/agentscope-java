# Agent Protocol

`agentscope-extensions-agent-protocol` exposes AgentScope's [Harness Agent](../../docs/harness/architecture.md) as a standard [Agent Protocol](https://agentprotocol.ai/) HTTP API, letting external systems (CI, other agent platforms, automation jobs) submit "tasks" using a uniform contract — no need to know the implementation details.

## When to use

- You want the Agent to be remotely scheduled like a cloud function.
- An existing team uses an Agent Protocol client and you'd like to plug in directly.
- You're embedding a Harness Agent in a Spring Boot service and want auto-exposed `/tasks` REST endpoints.
- You're hosting a [remote subagent](../../docs/harness/subagent.md#remote-subagent) that another Harness parent calls over HTTP.

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

The agent is stateless between calls — a singleton handles multiple concurrent tasks. Each task carries its own `(userId, sessionId)` via `RuntimeContext`, so state is fully isolated:

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
| `taskId()` | Task identifier; also the agent session id |
| `agentId()` | Requested `agent_id` from the submission |
| `input()` | User input; empty on a resume run |
| `userId()` / `parentSessionId()` | Parsed from `context.user_id` / `context.parent_session_id` |
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
| `stream` | Whether the caller intends to consume SSE events |
| `detail` | `status` (default), `full` or `verbose` — see [Stream detail levels](#stream-detail-levels) |
| `deny_rules` | Parent DENY permission rules to enforce on the remote side |
| `attributes` | Caller-defined key/values for routing and for the run's `RuntimeContext`; see [Context attributes](#context-attributes) |

Response on success: `{ "task_id", "status": "pending" }`.

### Poll / wait / cancel

| Method | Path | Notes |
| --- | --- | --- |
| `GET` | `/tasks/{taskId}` | Snapshot (`status`, result, errors, pending confirms) |
| `GET` | `/tasks/{taskId}/wait?timeout_seconds=7200` | Block until terminal (or `awaiting_confirm`) |
| `POST` | `/tasks/{taskId}/cancel` | Request cancellation |

While the remote agent waits for tool confirmation, the snapshot reports `status: awaiting_confirm` but the stored `TaskStatus` remains `RUNNING` so parent barriers keep waiting.

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
    { "toolCallId": "call-2", "approved": false }
  ]
}
```

`tool_call_id` is also accepted as an alias for `toolCallId`. Requires `agentscope.agent-protocol.hitl-enabled=true` (default). On success returns `{ "task_id", "status": "running" }`.

How remote HITL interacts with a calling parent harness is documented under [Remote authorization](../../docs/harness/subagent.md#remote-authorization).

## Configuration

| Property | Type | Default | Notes |
| --- | --- | --- | --- |
| `agentscope.agent-protocol.enabled` | boolean | `false` | Whether to register the `/tasks` REST endpoints |
| `agentscope.agent-protocol.streaming-enabled` | boolean | `true` | Expose SSE `GET /tasks/{id}/events` |
| `agentscope.agent-protocol.hitl-enabled` | boolean | `true` | Pause tasks for tool confirmation and accept `/resume` |
| `agentscope.agent-protocol.sse-replay-buffer-size` | int | `256` | Per-task replay buffer for late SSE subscribers |
| `agentscope.agent-protocol.sse-timeout-ms` | long | `10800000` | Max SSE subscription duration (ms) |

Example:

```yaml
agentscope:
  agent-protocol:
    enabled: true
    streaming-enabled: true
    hitl-enabled: true
    sse-replay-buffer-size: 256
    sse-timeout-ms: 10800000
```

> When `enabled` is `false` (the default) the dependency stays inert — no REST endpoints are exposed, safe to ship.

## Workspace integration

Each task receives an isolated workspace from `WorkspaceManager`. Once the task finishes, files and logs in the workspace are exposed via standard Agent Protocol endpoints so external clients can fetch artifacts.
