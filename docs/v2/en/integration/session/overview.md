---
title: Overview
---

<Note>

**Recommended: use [DistributedStore](/v2/en/integration/distributed/index) for one-line setup** — it covers AgentStateStore, BaseStore, SandboxSnapshotSpec, and SandboxExecutionGuard together. Read on if you only need to configure AgentStateStore individually.

</Note>


`io.agentscope.core.state.AgentStateStore` is the interface AgentScope uses to persist agent state — Memory, Workspace, Plan, and other components are serialized as `State` objects and stored via `AgentStateStore`, enabling restart recovery and cross-node sharing.

State is addressed by `(userId, sessionId)`:

- `sessionId` — required, non-blank, identifies a session.
- `userId` — optional. `null` means anonymous / single-tenant (CLI, tests, etc.).

## Available Implementations

| Implementation | Module | When to use |
| --- | --- | --- |
| `InMemoryAgentStateStore` | `agentscope-core` | Unit tests |
| `JsonFileAgentStateStore` | `agentscope-core` | Single-node dev (**HarnessAgent default**) |
| `RedisAgentStateStore` | `agentscope-extensions-redis` | [Multi-replica production default](/v2/en/integration/distributed/redis) |
| `MysqlAgentStateStore` | `agentscope-extensions-mysql` | [Existing database infrastructure](/v2/en/integration/distributed/mysql) |
| `OssAgentStateStore` | `agentscope-extensions-oss` | [Alibaba Cloud ecosystem](/v2/en/integration/distributed/oss) |

## Standalone Configuration

```java
ReActAgent agent = ReActAgent.builder()
    .name("assistant")
    .model(model)
    .stateStore(stateStore)   // any AgentStateStore implementation
    .build();
```

For detailed usage and code examples, see each store's documentation:

- [Redis](/v2/en/integration/distributed/redis#1-redisagentstatestore)
- [MySQL](/v2/en/integration/distributed/mysql#1-mysqlagentstatestore)
- [OSS](/v2/en/integration/distributed/oss#1-ossagentstatestore)
