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

## Limit Persisted Conversation History

By default, the complete conversation context is included in every persisted `agent_state`
snapshot. For long-running sessions, set a maximum number of recent messages to keep the stored
record bounded. This works with both a standalone `AgentStateStore` and a `DistributedStore`:

```java
HarnessAgent agent = HarnessAgent.builder()
    .name("assistant")
    .model(model)
    .distributedStore(distributedStore)
    .maxPersistedContextMessages(200)
    .build();
```

The default is unlimited for backward compatibility. A value of `0` persists the rest of the agent
state without conversation messages. Trimming creates a persistence snapshot and does not mutate
the live state of the current call.

Applications can also choose when to remove an entire session:

```java
agent.deleteSessionState(userId, sessionId);
// or: agent.deleteSessionState(runtimeContext);
```

This removes the session from the configured store and evicts its local state caches. Call it only
after active work for that session has stopped; an in-flight call can persist the session again when
it completes.

For detailed usage and code examples, see each store's documentation:

- [Redis](../distributed/redis.md#1-redisagentstatestore)
- [MySQL](../distributed/mysql.md#1-mysqlagentstatestore)
- [OSS](../distributed/oss.md#1-ossagentstatestore)
