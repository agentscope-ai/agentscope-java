---
title: Redis
zh_link: /v2/zh/integration/distributed/redis
---

`agentscope-extensions-redis` provides full-stack Redis distributed storage — the recommended store for multi-replica production deployments.

## Dependency

```xml
<dependency>
    <groupId>io.agentscope</groupId>
    <artifactId>agentscope-extensions-redis</artifactId>
    <version>${agentscope.version}</version>
</dependency>
```

The module does not force a specific Redis client — import whichever you use (Jedis / Lettuce / Redisson).

## One-Line Setup

```java
import io.agentscope.extensions.redis.RedisDistributedStore;

JedisPooled jedis = new JedisPooled("redis://localhost:6379");
DistributedStore store = RedisDistributedStore.fromJedis(jedis);

HarnessAgent agent = HarnessAgent.builder()
    .distributedStore(store)
    .filesystem(new RemoteFilesystemSpec()
            .isolationScope(IsolationScope.USER))
    .build();
```

Custom key prefix for multi-environment isolation:

```java
DistributedStore store = RedisDistributedStore.fromJedis(jedis, "prod:");
```

For Jedis Cluster, select the cluster-safe agent-state key layout explicitly:

```java
import io.agentscope.extensions.redis.state.RedisAgentStateStore;

DistributedStore store = RedisDistributedStore.fromJedis(
    jedisCluster,
    "prod:",
    RedisAgentStateStore.KeyLayoutVersion.V1);
```

## Components Provided

### 1. RedisAgentStateStore

Agent state persisted to Redis. Supports Jedis / Lettuce / Redisson.

```java
import io.agentscope.extensions.redis.state.RedisAgentStateStore;

// Jedis
AgentStateStore store = RedisAgentStateStore.builder()
    .jedisClient(new JedisPooled("redis://localhost:6379"))
    .keyPrefix("myapp:session:")
    .build();

// Lettuce Cluster
AgentStateStore store = RedisAgentStateStore.builder()
    .lettuceClusterClient(RedisClusterClient.create(RedisURI.create("localhost", 7000)))
    .keyLayoutVersion(RedisAgentStateStore.KeyLayoutVersion.V1)
    .build();

// Redisson
Config config = new Config();
config.useSingleServer().setAddress("redis://localhost:6379");
AgentStateStore store = RedisAgentStateStore.builder()
    .redissonClient(Redisson.create(config))
    .build();
```

#### V0 and V1 key layouts

`RedisAgentStateStore` uses V0 by default to preserve existing Redis keys. Select V1 explicitly for
Redis Cluster, and configure the same version on every application instance that shares a key
prefix.

For example, given:

```text
keyPrefix = agentscope:session:
userId = alice
sessionId = s1
```

the layouts differ only in the session segment: V0 uses `alice/s1`, while V1 wraps that segment in
the Redis hash tag `{alice/s1}`.

| Data | V0 key | V1 key |
|---|---|---|
| Single state `agent_state` | `agentscope:session:alice/s1:agent_state` | `agentscope:session:{alice/s1}:agent_state` |
| State version | `agentscope:session:alice/s1:agent_state:ver` | `agentscope:session:{alice/s1}:agent_state:ver` |
| List state `messages` | `agentscope:session:alice/s1:messages:list` | `agentscope:session:{alice/s1}:messages:list` |
| List change-detection hash | `agentscope:session:alice/s1:messages:list:_hash` | `agentscope:session:{alice/s1}:messages:list:_hash` |
| Session index | `agentscope:session:alice/s1:_keys` | `agentscope:session:{alice/s1}:_keys` |

The session index is a Redis Set. Its members do not change between layouts: the single-state member
is stored as `agent_state`, and a list member as `messages:list`. A missing or blank `userId` is
normalized to `__anon__` in both layouts.

V0 has no hash tag, so Redis Cluster hashes each complete key independently. The state key,
version key, and session-index key used by the optimistic-concurrency Lua script can therefore land
in different slots and cause `CROSSSLOT`. With V1, Redis hashes only the shared `alice/s1` text
inside `{...}`, placing every key for that session in one slot.

V0 and V1 are separate key namespaces. A store configured for one layout uses only that layout for
reads, writes, `exists`, `delete`, and `listSessionIds`; it does not read data from the other layout.
V1 also applies these structural restrictions:

- `keyPrefix` must not contain `{` or `}`.
- `userId` and `sessionId` must not contain `{`, `}`, or `/`.
- `sessionId` must not be null or blank.

#### Migrating from V0 to V1

The library does not automatically migrate or dual-write state. The recommended migration is a
stop-write cutover for each key prefix.

1. **Inventory and validate.** Identify every application instance using the prefix and inventory
   the `(userId, sessionId)` pairs that must be retained. Resolve identifiers that violate the V1
   restrictions before migration. Do not infer ambiguous identifiers containing `/` only by
   splitting V0 key names; use the application's authoritative IDs.
2. **Choose whether to retain old state.** If agent state is disposable or already governed by a
   short TTL, stop writers, remove or expire the V0 keys, and start with V1. Otherwise continue with
   the copy procedure below.
3. **Stop writes and back up Redis.** Drain in-flight requests and prevent all instances from
   writing to the prefix. Take a backup or snapshot before changing keys. Copying live V0 keys
   without a write barrier can produce mismatched values, versions, and session indexes.
4. **Copy every session one key at a time.** For each V0 session, change only the session segment
   from `userId/sessionId` to `{userId/sessionId}`. Copy all existing keys: single-state values,
   version keys, lists, list hashes, and the `_keys` session index. Copy the session index last so a
   partially copied session is not reported as present.
5. **Preserve type and TTL.** A migration tool can use `DUMP`, `PTTL`, and `RESTORE ... REPLACE` for
   each individual key. Treat `PTTL = -1` as a non-expiring destination (`RESTORE` TTL `0`) and skip
   a key if `PTTL = -2`. Use a cluster-aware client so each source and destination command is routed
   to the correct node. Do not use `RENAME` or a multi-key copy/delete command across slots.
6. **Verify before cutover.** Compare the V0 and V1 values, Redis types, TTLs, version numbers, list
   contents, and `_keys` members for a representative sample and for total session counts. Run
   `CLUSTER KEYSLOT` for the V1 state, version, and index keys and confirm that the slot numbers are
   identical. Perform application smoke tests for load, save, compare-and-set, list, and delete on
   test sessions.
7. **Switch all instances together.** Configure every store using the prefix with
   `KeyLayoutVersion.V1`, deploy the instances as one coordinated cutover, and then resume traffic.
   Running V0 and V1 writers concurrently creates two independently changing copies of a session.
8. **Keep V0 data during an observation window.** Do not modify the retained V0 copy after the V1
   cutover. If rollback is required before any V1 write, all instances can return to V0. After V1
   writes begin, stop writes again and reverse-migrate the changed data before switching back;
   otherwise the V1 updates will be invisible. V0 also reintroduces the original Cluster
   `CROSSSLOT` limitation.
9. **Remove V0 data selectively.** After the observation window, delete only the old V0 keys, one
   key per command. Do not use `clearAllSessions()` for this cleanup because it deletes both V0 and
   V1 keys under the configured prefix.

### 2. RedisStore (BaseStore)

Workspace filesystem KV storage for `RemoteFilesystemSpec`.

```java
import io.agentscope.extensions.redis.store.RedisStore;

BaseStore store = new RedisStore(jedis);
BaseStore store = new RedisStore(jedis, "myapp:store:");
```

Concurrency-safe: `put` / `putIfVersion` use Lua scripts for atomicity.

### 3. RedisSnapshotSpec

Sandbox snapshots stored as Redis binary keys. Best for small workspaces + short TTL.

```java
import io.agentscope.extensions.redis.snapshot.RedisSnapshotSpec;

SandboxSnapshotSpec spec = new RedisSnapshotSpec(jedis, "myapp:snapshot:", 3600);
```

### 4. RedisSandboxExecutionGuard

Redis `SET NX PX` lease-based distributed lock for multi-replica sandbox concurrency control.

```java
import io.agentscope.extensions.redis.sandbox.RedisSandboxExecutionGuard;

SandboxExecutionGuard guard = RedisSandboxExecutionGuard.builder(jedis)
    .keyPrefix("myapp:guard:")
    .leaseTtl(Duration.ofMinutes(30))
    .retryInterval(Duration.ofMillis(500))
    .build();
```

## When to Use

| Scenario | Recommendation |
|----------|---------------|
| Multi-replica production, low latency | **First choice**: Redis |
| Existing Redis cluster | Lettuce Cluster or Redisson Sentinel |
| Small workspace + short TTL snapshots | Redis snapshots work, watch memory |
| Large workspace snapshots | Mixed store: Redis for state/lock, OSS for snapshots |
