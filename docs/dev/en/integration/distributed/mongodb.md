---
title: MongoDB
zh_link: /dev/zh/integration/distributed/mongodb
---

# MongoDB

`agentscope-extensions-mongodb` provides full-stack MongoDB distributed storage — ideal for deployments that already run MongoDB or prefer a document-oriented backend for agent runtime data.

## Dependency

```xml
<dependency>
    <groupId>io.agentscope</groupId>
    <artifactId>agentscope-extensions-mongodb</artifactId>
    <version>${agentscope.version}</version>
</dependency>
```

The module depends on the official MongoDB Java Driver. No additional client library is required.

## One-Line Setup

```java
import io.agentscope.extensions.mongodb.MongoDistributedStore;

MongoClient mongoClient = MongoClients.create("mongodb://localhost:27017");
DistributedStore store = MongoDistributedStore.create(mongoClient);

HarnessAgent agent = HarnessAgent.builder()
    .distributedStore(store)
    .filesystem(new RemoteFilesystemSpec()
            .isolationScope(IsolationScope.USER))
    .build();
```

From a connection string (the store owns the client lifecycle):

```java
DistributedStore store = MongoDistributedStore.fromConnectionString(
        "mongodb://user:pass@host:27017/mydb?authSource=admin");
```

When the connection string includes a database path (e.g. `/mydb`), that database is used instead of the default `agentscope`.

## Components Provided

### 1. MongoAgentStateStore

Agent session state persisted to MongoDB. Each session is a single document with a compound `_id` of `{user, session}`. State keys are stored inside a `states` sub-document (via dot notation), keeping them isolated from reserved top-level fields.

```java
import io.agentscope.extensions.mongodb.state.MongoAgentStateStore;

AgentStateStore store = MongoAgentStateStore.builder()
    .mongoClient(mongoClient)
    .databaseName("agentscope")
    .collectionName("agentscope_sessions")
    .build();
```

- **Single value**: stored at `states.<key>` as a BSON sub-document; version at `versions.<key>`.
- **List value**: stored at `states.<key>` as a BSON array; content hash at `hashes.<key>` for incremental-append optimization.
- **TTL**: 30-day expiry on `_updated_at` (sparse index).

### 2. MongoBaseStore (BaseStore)

Workspace filesystem KV storage for `RemoteFilesystemSpec`. Uses prefix-matching via range queries on a compound `(namespace, key)` index — consistent with `InMemoryStore` and `PostgresBaseStore`.

```java
import io.agentscope.extensions.mongodb.store.MongoBaseStore;

BaseStore store = new MongoBaseStore(mongoClient.getDatabase("agentscope"), "agentscope_base");
```

- Namespace paths use `\u001F` (ASCII Unit Separator) as the segment delimiter, with a trailing separator enabling prefix matching.
- `put` / `putIfVersion` support optimistic concurrency via a `version` field.

### 3. MongoSnapshotSpec

Sandbox snapshots are stored in GridFS, using separate file metadata and chunk collections. There is no independent snapshot TTL. Deleting the owning session explicitly triggers snapshot cleanup; expiring a session through MongoDB's TTL mechanism does not run that application callback.

```java
import io.agentscope.extensions.mongodb.snapshot.MongoSnapshotSpec;

SandboxSnapshotSpec spec = new MongoSnapshotSpec(mongoClient, "agentscope");
```

Replacing a snapshot uploads the new archive before deleting previously completed revisions. If the upload stream fails, the last completed archive remains available. Downloads select the newest completed file by `uploadDate`, with `_id` as a deterministic tie-breaker if two timestamps match. An interrupted cleanup can leave multiple completed revisions; the next replacement cleans up the revisions it observed, and explicit snapshot deletion removes all matching revisions and any legacy BSON snapshot. Concurrent writers still require application-level coordination; this is not a transactional snapshot/agent-state update.

GridFS chunks avoid the 16 MB limit of the legacy single-document format. Existing BSON Binary snapshots remain readable as a fallback when no completed GridFS file exists.

### 4. MongoSandboxExecutionGuard

Distributed lock using a dedicated MongoDB collection with a TTL index on `expiresAt`. Each acquisition generates a random UUID token for safe renewal and release — two guard instances in the same JVM cannot accidentally release each other's locks.

```java
import io.agentscope.extensions.mongodb.sandbox.MongoSandboxExecutionGuard;

SandboxExecutionGuard guard = MongoSandboxExecutionGuard.builder(mongoClient)
    .databaseName("agentscope")
    .collectionName("agentscope_sandbox_locks")
    .lockTimeout(Duration.ofMinutes(30))
    .leaseTtl(Duration.ofMinutes(30))
    .retryInterval(Duration.ofMillis(500))
    .build();
```

- `lockTimeout` bounds acquisition wait only; `leaseTtl` controls the lock document lifetime.
- A background watchdog renews the lease every `leaseTtl / 3` using a 2-thread pool.
- The guard implements `AutoCloseable` and is shut down by `MongoDistributedStore.close()`.

## Collections and TTL

| Collection | Purpose | TTL | Default Name |
|------------|---------|-----|--------------|
| Sessions | Agent state store | 30 days on `_updated_at` | `agentscope_sessions` |
| Base store | Workspace KV | — | `agentscope_base` |
| Snapshots | Sandbox tar archives | No independent TTL | `agentscope_snapshots.files` / `agentscope_snapshots.chunks` |
| Sandbox locks | Distributed lock | Immediate on `expiresAt` | `agentscope_sandbox_locks` |

All collection names are centralised in `MongoConstants` and can be overridden per component.

## When to Use

| Scenario | Recommendation |
|----------|---------------|
| Existing MongoDB cluster | **First choice**: MongoDB |
| Document-oriented storage preferred | MongoDB |
| Large conversation histories (unbounded field growth) | MongoDB (no MySQL field-length limits) |
| Large workspace snapshots (>15 MB) | GridFS snapshots, or a mixed store with OSS |
