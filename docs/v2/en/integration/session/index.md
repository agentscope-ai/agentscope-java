# Agent State Store (AgentStateStore)

```{note}
**Recommended: use [DistributedStore](../distributed/index.md) for one-line setup** — it covers AgentStateStore, BaseStore, SandboxSnapshotSpec, and SandboxExecutionGuard together. Read on if you only need to configure AgentStateStore individually.
```

`io.agentscope.core.state.AgentStateStore` is the interface AgentScope uses to persist agent state — Memory, Workspace, Plan, and other components are serialized as `State` objects and stored via `AgentStateStore`, enabling restart recovery and cross-node sharing.

State is addressed by `(userId, sessionId)`:

- `sessionId` — required, non-blank, identifies a session.
- `userId` — optional. `null` means anonymous / single-tenant (CLI, tests, etc.).

## Available Implementations

| Implementation | Module | When to use |
| --- | --- | --- |
| `InMemoryAgentStateStore` | `agentscope-core` | Unit tests |
| `JsonFileAgentStateStore` | `agentscope-core` | Single-node dev (**HarnessAgent default**) |
| `RedisAgentStateStore` | `agentscope-extensions-redis` | [Multi-replica production default](../distributed/redis.md) |
| `MysqlAgentStateStore` | `agentscope-extensions-mysql` | [Existing database infrastructure](../distributed/mysql.md) |
| `OssAgentStateStore` | `agentscope-extensions-oss` | [Alibaba Cloud ecosystem](../distributed/oss.md) |

## Standalone Configuration

```java
ReActAgent agent = ReActAgent.builder()
    .name("assistant")
    .model(model)
    .stateStore(stateStore)   // any AgentStateStore implementation
    .build();
```

For detailed usage and code examples, see each store's documentation:

- [Redis](../distributed/redis.md#1-redisagentstatestore)
- [MySQL](../distributed/mysql.md#1-mysqlagentstatestore)
- [OSS](../distributed/oss.md#1-ossagentstatestore)
