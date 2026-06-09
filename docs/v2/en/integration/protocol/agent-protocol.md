# Agent Protocol

`agentscope-extensions-agent-protocol` exposes AgentScope's [Harness Agent](../../docs/harness/architecture) as a standard [Agent Protocol](https://agentprotocol.ai/) HTTP API, letting external systems (CI, other agent platforms, automation jobs) submit "tasks" using a uniform contract — no need to know the implementation details.

## When to use

- You want the Agent to be remotely scheduled like a cloud function.
- An existing team uses an Agent Protocol client and you'd like to plug in directly.
- You're embedding a Harness Agent in a Spring Boot service and want auto-exposed `/tasks` REST endpoints.

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

The `/tasks` REST endpoints (per the Agent Protocol spec) are then registered automatically.

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

## Configuration

| Property | Type | Default | Notes |
| --- | --- | --- | --- |
| `agentscope.agent-protocol.enabled` | boolean | `false` | Whether to register the `/tasks` REST endpoints |

> When disabled (the default) the dependency stays inert — no REST endpoints are exposed, safe to ship.

## Workspace integration

Each task receives an isolated workspace from `WorkspaceManager`. Once the task finishes, files and logs in the workspace are exposed via standard Agent Protocol endpoints so external clients can fetch artifacts.
