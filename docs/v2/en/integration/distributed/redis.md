---
title: Redis
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

Choose Jedis, Lettuce, or Redisson to match your project. The Redisson state-store integration requires the **Redisson 4.x API** (`RScript.ReturnType.LONG`); Redisson 3.x is incompatible. AgentScope currently manages and tests against **4.2.0**. For example, align a direct Redisson dependency with that version:

```xml
<dependency>
    <groupId>org.redisson</groupId>
    <artifactId>redisson</artifactId>
    <version>4.2.0</version>
</dependency>
```

If you manage your own Redisson Spring Boot starter, a 3.x starter (for example, 3.52.0) is incompatible with this state-store integration. Align the starter and its Redisson dependencies with the current 4.2.0 baseline and check the resolved runtime dependencies. Both `RedisAgentStateStore.builder().redissonClient(...)` and the deprecated `RedissonAgentStateStore` fail during construction when the loaded API lacks `LONG`. This check reports the incompatibility; it does not repair dependency resolution or make Redisson 3.x persistence supported.

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
    .build();

// Redisson
Config config = new Config();
config.useSingleServer().setAddress("redis://localhost:6379");
AgentStateStore store = RedisAgentStateStore.builder()
    .redissonClient(Redisson.create(config))
    .build();
```

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
