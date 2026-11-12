---
title: Overview
zh_link: /dev/zh/integration/session/overview
---

<Note>

**Recommended: use [DistributedStore](/dev/en/integration/distributed/index) for one-line setup** — it covers AgentStateStore, BaseStore, SandboxSnapshotSpec, and SandboxExecutionGuard together. Read on if you only need to configure AgentStateStore individually.

</Note>


`io.agentscope.core.state.AgentStateStore` is the interface AgentScope uses to persist agent state — Memory, Workspace, Plan, and other components are serialized as `State` objects and stored via `AgentStateStore`, enabling restart recovery and cross-node sharing.

State is addressed by `(userId, sessionId)`:

- `sessionId` — required, non-blank, identifies a session.
- `userId` — optional. `null` means anonymous / single-tenant (CLI, tests, etc.).

## AgentStateStore and the Thread journal

This page covers `AgentStateStore`. With journaling enabled, `HarnessAgent` recovers from its
`ThreadJournalStore`; changing only `stateStore` does not move or share journal history. The main
Thread also reads state written by AgentScope 2.0 and mirrors each committed checkpoint back to the
2.0 `agent_state` slot for rollback compatibility. Named Threads use independent state entries.
A file-capable BaseStore does not necessarily provide the journal CAS and fencing contract. See
[Thread journals and recovery](/dev/en/docs/harness/session-log).

## Available Implementations

| Implementation | Module | When to use |
| --- | --- | --- |
| `InMemoryAgentStateStore` | `agentscope-core` | Unit tests |
| `JsonFileAgentStateStore` | `agentscope-core` | Single-node dev (**HarnessAgent default**) |
| `RedisAgentStateStore` | `agentscope-extensions-redis` | [Multi-replica production default](/dev/en/integration/distributed/redis) |
| `JdbcAgentStateStore` | `agentscope-extensions-jdbc` | [Existing database infrastructure](/dev/en/integration/distributed/jdbc) |
| `OssAgentStateStore` | `agentscope-extensions-oss` | [Alibaba Cloud ecosystem](/dev/en/integration/distributed/oss) |

## Standalone Configuration

```java
ReActAgent agent = ReActAgent.builder()
    .name("assistant")
    .model(model)
    .stateStore(stateStore)   // any AgentStateStore implementation
    .build();
```

For detailed usage and code examples, see each store's documentation:

- [Redis](/dev/en/integration/distributed/redis#1-redisagentstatestore)
- [JDBC](/dev/en/integration/distributed/jdbc#1-jdbcagentstatestore)
- [OSS](/dev/en/integration/distributed/oss#1-ossagentstatestore)
