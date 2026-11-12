---
title: Distributed Storage (Distributed Store)
zh_link: /dev/zh/integration/distributed/index
---

AgentScope unifies all components that need distributed persistence under the `DistributedStore` interface. One line of configuration switches agent state, workspace filesystem, sandbox snapshots, and concurrency locks to the same distributed store.

## Quick Start

```java
// Redis — one-line setup
DistributedStore store = RedisDistributedStore.fromJedis(
        new JedisPooled("redis://localhost:6379"));

HarnessAgent agent = HarnessAgent.builder()
    .name("my-agent")
    .model("dashscope:qwen-plus")
    .distributedStore(store)
    .filesystem(new RemoteFilesystemSpec()            // baseStore auto-injected
            .isolationScope(IsolationScope.USER))
    .build();
```

## Capability Matrix

| Component | Interface | Redis | OSS | JDBC | MongoDB |
|-----------|----------|:-----:|:---:|:-----:|:-------:|
| Agent state persistence | `AgentStateStore` | `RedisAgentStateStore` | `OssAgentStateStore` | `JdbcAgentStateStore` | `MongoAgentStateStore` |
| Workspace filesystem KV | `BaseStore` | `RedisStore` | `OssBaseStore` | `JdbcStore` | `MongoBaseStore` |
| Sandbox snapshots | `SandboxSnapshotSpec` | `RedisSnapshotSpec` | `OssSnapshotSpec` | `JdbcSnapshotSpec` | `MongoSnapshotSpec` |
| Sandbox concurrency lock | `SandboxExecutionGuard` | `RedisSandboxExecutionGuard` | — | `JdbcSandboxExecutionGuard` | `MongoSandboxExecutionGuard` |

> OSS does not provide `SandboxExecutionGuard` — object storage is unsuitable for distributed locking. Mix in a Redis guard via `DistributedStore.builder()`.

## Mixed Stores

Different components can come from different storage stores:

```java
DistributedStore jdbc = JdbcDistributedStore.create(dataSource);
DistributedStore redis = RedisDistributedStore.fromJedis(jedis);

// JDBC for state and files, Redis for sandbox lock and snapshots
DistributedStore mixed = DistributedStore.builder()
    .agentStateStore(jdbc.agentStateStore())
    .baseStore(jdbc.baseStore())
    .sandboxSnapshotSpec(redis.sandboxSnapshotSpec())
    .sandboxExecutionGuard(redis.sandboxExecutionGuard())
    .build();

HarnessAgent.builder()
    .distributedStore(mixed)
    .filesystem(new DockerFilesystemSpec()
            .image("ubuntu:24.04"))
    .build();
```

## Components

### AgentStateStore — Agent State Persistence

Conversation context, compaction summaries, permission rules, Plan Mode state, addressed by `(userId, sessionId)`. Auto-wired by `distributedStore`; can be overridden via `.stateStore(...)`.

### BaseStore — Workspace Filesystem KV

Storage provider for `RemoteFilesystemSpec`, routing `MEMORY.md`, `memory/`, `skills/`, `sessions/` to shared KV storage. Auto-injected into `RemoteFilesystemSpec` when using the no-arg constructor.

### SandboxSnapshotSpec — Sandbox Snapshots

Persists Docker/K8s sandbox workspace as tar archives for cross-call recovery. Auto-wired into `SandboxFilesystemSpec` by `distributedStore`.

### SandboxExecutionGuard — Sandbox Concurrency Lock

Distributed lock for `AGENT` / `GLOBAL` isolation scope under multi-replica deployment. Auto-wired into `SandboxFilesystemSpec` by `distributedStore`.

## Priority

```
Explicit builder methods (.stateStore(), .snapshotSpec() on FilesystemSpec, etc.)
    > distributedStore auto-wiring
        > local defaults (JsonFileAgentStateStore, NoopSnapshotSpec, etc.)
```

## Store Documentation

- [Redis](/dev/en/integration/distributed/redis) — full capability coverage, recommended for multi-replica production
- [JDBC](/dev/en/integration/distributed/jdbc) — for existing relational database infrastructure
- [MongoDB](/dev/en/integration/distributed/mongodb) — document-oriented storage, ideal for large conversation histories
- [Alibaba Cloud OSS](/dev/en/integration/distributed/oss) — object storage, best for large-capacity snapshots
