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

1. Provide a `HarnessAgent` bean and a `WorkspaceManager` bean.
2. Enable in `application.yml`:

```yaml
agentscope:
  agent-protocol:
    enabled: true
```

The `/tasks` REST endpoints are then registered automatically.

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
    ]
  }
}
```

Optional `context` fields:

| Field | Notes |
| --- | --- |
| `user_id` | Forwarded into the remote agent's `RuntimeContext` |
| `parent_session_id` | Parent session identity (for tracing / correlation) |
| `stream` | Whether the caller intends to consume SSE events |
| `detail` | `status` (default) or `full` — `full` includes text/thinking deltas on the event stream |
| `deny_rules` | Parent DENY permission rules to enforce on the remote side |

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
