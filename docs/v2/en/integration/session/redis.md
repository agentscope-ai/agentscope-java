```{note}
This page has been superseded by [Distributed Storage — Redis](../distributed/redis.md). Content below is kept for reference.
```

# Redis State Store

`agentscope-extensions-redis` persists AgentScope agent state in Redis. The unified `RedisClientAdapter` abstracts over **Jedis, Lettuce, and Redisson**, covering Standalone, Cluster, and Sentinel deployment modes.

## Add the dependency

```xml
<dependency>
    <groupId>io.agentscope</groupId>
    <artifactId>agentscope-extensions-redis</artifactId>
    <version>${agentscope.version}</version>
</dependency>
```

The module does not pin a Redis client — bring whatever you already use (Jedis / Lettuce / Redisson).

## Quickstart (Lettuce, standalone)

```java
import io.lettuce.core.RedisClient;
import io.agentscope.core.state.AgentStateStore;
import io.agentscope.extensions.redis.state.RedisAgentStateStore;

RedisClient redisClient = RedisClient.create("redis://localhost:6379");

AgentStateStore stateStore = RedisAgentStateStore.builder()
    .lettuceClient(redisClient)
    .build();
```

## Wiring each client

### Jedis

```java
import redis.clients.jedis.UnifiedJedis;

UnifiedJedis jedis = new redis.clients.jedis.JedisPooled("localhost", 6379);
AgentStateStore stateStore = RedisAgentStateStore.builder()
    .jedisClient(jedis)   // UnifiedJedis, JedisCluster, JedisSentineled all work
    .build();
```

### Lettuce cluster

```java
import io.lettuce.core.cluster.RedisClusterClient;
import io.lettuce.core.RedisURI;

RedisClusterClient clusterClient = RedisClusterClient.create(
    RedisURI.create("redis://localhost:7000"));

AgentStateStore stateStore = RedisAgentStateStore.builder()
    .lettuceClusterClient(clusterClient)
    .build();
```

### Redisson

```java
import org.redisson.Redisson;
import org.redisson.config.Config;

Config config = new Config();
config.useSingleServer().setAddress("redis://localhost:6379");
RedissonClient redisson = Redisson.create(config);

AgentStateStore stateStore = RedisAgentStateStore.builder()
    .redissonClient(redisson)
    .build();
```

> Redisson also supports `useClusterServers()` / `useSentinelServers()` / `useMasterSlaveServers()`; pass the resulting `RedissonClient` to `redissonClient(...)`.

## Custom key prefix

By default, all keys look like `agentscope:session:{userSegment}/{sessionId}:...` (where `userSegment` is the `userId`, or `__anon__` for anonymous sessions). When several projects share the same Redis, override it:

```java
AgentStateStore stateStore = RedisAgentStateStore.builder()
    .lettuceClient(redisClient)
    .keyPrefix("myapp:session:")
    .build();
```

## Key layout

The `(userId, sessionId)` pair is packed into a single slot id `{userSegment}/{sessionId}` (`userSegment` = `userId`, or `__anon__` when `userId` is null).

| Type | Key pattern |
| --- | --- |
| Single value | `{prefix}{userSegment}/{sessionId}:{stateKey}` (Redis String, JSON value) |
| List | `{prefix}{userSegment}/{sessionId}:{stateKey}:list` (Redis List, one JSON item per element) |
| List hash | `{prefix}{userSegment}/{sessionId}:{stateKey}:list:_hash` (change detection) |
| Session index | `{prefix}{userSegment}/{sessionId}:_keys` (Redis Set tracking all stateKeys) |

The `_keys` index makes `delete(userId, sessionId)` and `exists(userId, sessionId)` O(1) without needing `KEYS *`.

## Wire into an agent

```java
ReActAgent agent = ReActAgent.builder()
    .name("assistant")
    .model(model)
    .stateStore(stateStore)
    .build();
```

After this, your Memory, Workspace, Plan, etc. are persisted through Redis automatically. The slot each call reads / writes is chosen per-call from the `RuntimeContext`:

```java
RuntimeContext rc = RuntimeContext.builder()
    .userId("alice")
    .sessionId("session-1")
    .build();

agent.call(msg, rc).block();
```

## Custom adapter

If you target a Redis-compatible store (KeyDB, Tair, ...), implement `RedisClientAdapter` and inject it via `clientAdapter(...)`:

```java
AgentStateStore stateStore = RedisAgentStateStore.builder()
    .clientAdapter(new MyCustomAdapter(...))
    .build();
```

## Builder reference

| Method | Notes |
| --- | --- |
| `jedisClient(UnifiedJedis)` | Jedis standalone / cluster / sentinel |
| `lettuceClient(RedisClient)` | Lettuce standalone / sentinel |
| `lettuceClusterClient(RedisClusterClient)` | Lettuce cluster |
| `redissonClient(RedissonClient)` | Redisson, any deployment mode |
| `clientAdapter(RedisClientAdapter)` | Custom adapter |
| `keyPrefix(String)` | Default `agentscope:session:` |

> The client setters are mutually exclusive — set exactly one.
