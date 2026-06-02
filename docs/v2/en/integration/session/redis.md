# Redis Session

`agentscope-extensions-session-redis` persists AgentScope session state in Redis. The unified `RedisClientAdapter` abstracts over **Jedis, Lettuce, and Redisson**, covering Standalone, Cluster, and Sentinel deployment modes.

## Add the dependency

```xml
<dependency>
    <groupId>io.agentscope</groupId>
    <artifactId>agentscope-extensions-session-redis</artifactId>
    <version>${agentscope.version}</version>
</dependency>
```

The module does not pin a Redis client — bring whatever you already use (Jedis / Lettuce / Redisson).

## Quickstart (Lettuce, standalone)

```java
import io.lettuce.core.RedisClient;
import io.agentscope.core.session.Session;
import io.agentscope.core.session.redis.RedisSession;

RedisClient redisClient = RedisClient.create("redis://localhost:6379");

Session session = RedisSession.builder()
    .lettuceClient(redisClient)
    .build();
```

## Wiring each client

### Jedis

```java
import redis.clients.jedis.UnifiedJedis;

UnifiedJedis jedis = new redis.clients.jedis.JedisPooled("localhost", 6379);
Session session = RedisSession.builder()
    .jedisClient(jedis)   // UnifiedJedis, JedisCluster, JedisSentineled all work
    .build();
```

### Lettuce cluster

```java
import io.lettuce.core.cluster.RedisClusterClient;
import io.lettuce.core.RedisURI;

RedisClusterClient clusterClient = RedisClusterClient.create(
    RedisURI.create("redis://localhost:7000"));

Session session = RedisSession.builder()
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

Session session = RedisSession.builder()
    .redissonClient(redisson)
    .build();
```

> Redisson also supports `useClusterServers()` / `useSentinelServers()` / `useMasterSlaveServers()`; pass the resulting `RedissonClient` to `redissonClient(...)`.

## Custom key prefix

By default, all keys look like `agentscope:session:{sessionId}:...`. When several projects share the same Redis, override it:

```java
Session session = RedisSession.builder()
    .lettuceClient(redisClient)
    .keyPrefix("myapp:session:")
    .build();
```

## Key layout

| Type | Key pattern |
| --- | --- |
| Single value | `{prefix}{sessionId}:{stateKey}` (Redis String, JSON value) |
| List | `{prefix}{sessionId}:{stateKey}:list` (Redis List, one JSON item per element) |
| List hash | `{prefix}{sessionId}:{stateKey}:list:_hash` (change detection) |
| Session index | `{prefix}{sessionId}:_keys` (Redis Set tracking all stateKeys) |

The `_keys` index makes `delete(sessionKey)` and `exists(sessionKey)` O(1) without needing `KEYS *`.

## Wire into SessionManager

```java
SessionManager manager = SessionManager.builder()
    .session(session)
    .build();
```

After this, your Memory, Workspace, Plan, etc. are persisted through Redis automatically.

## Custom adapter

If you target a Redis-compatible store (KeyDB, Tair, ...), implement `RedisClientAdapter` and inject it via `clientAdapter(...)`:

```java
Session session = RedisSession.builder()
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
