---
title: Overview
zh_link: /v2/zh/integration/session/overview
---

<Note>

**Recommended: use [DistributedStore](/v2/en/integration/distributed/index) for one-line setup** — it covers AgentStateStore, BaseStore, SandboxSnapshotSpec, and SandboxExecutionGuard together. Read on if you only need to configure AgentStateStore individually.

</Note>


`io.agentscope.core.state.AgentStateStore` is the interface AgentScope uses to persist agent state — Memory, Workspace, Plan, and other components are serialized as `State` objects and stored via `AgentStateStore`, enabling restart recovery and cross-node sharing.

State is addressed by `(userId, sessionId)`:

- `sessionId` — required, non-blank, identifies a session.
- `userId` — optional. `null` means anonymous / single-tenant (CLI, tests, etc.); blank and reserved `__anon__` use the same anonymous namespace.

Per-call saves use the exact caller identity used for loading. Session IDs containing `/`, including trailing slashes, are supported without splitting or rewriting the identity. Rows saved under corrupted identities by older versions are not automatically migrated. The internal slot key remains unchanged; avoid ambiguous pairs such as `(u1/a, b)` and `(u1, a/b)` on one agent instance.

## Available Implementations

| Implementation | Module | When to use |
| --- | --- | --- |
| `InMemoryAgentStateStore` | `agentscope-core` | Unit tests |
| `JsonFileAgentStateStore` | `agentscope-core` | Single-node dev (**HarnessAgent default**) |
| `RedisAgentStateStore` | `agentscope-extensions-redis` | [Multi-replica production default](/v2/en/integration/distributed/redis) |
| `MysqlAgentStateStore` | `agentscope-extensions-mysql` | [Existing database infrastructure](/v2/en/integration/distributed/mysql) |
| `OssAgentStateStore` | `agentscope-extensions-oss` | [Alibaba Cloud ecosystem](/v2/en/integration/distributed/oss) |

## File-store concurrency

`JsonFileAgentStateStore` uses unique temporary siblings with cleanup and a JVM-wide lock per session directory, shared across store instances and root aliases. Reads, saves and deletions for one session are serialized; different sessions remain independent. This is not a cross-process lock: one process must own each session. Use a database-backed store for shared multi-process sessions.

Separate read-modify-write calls remain last-writer-wins. List data and hash files are not a two-file transaction; a failed hash write can leave a stale hash until the next save. Session listing and clearing are not global transactions.

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
