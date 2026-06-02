# Redis Session

`agentscope-extensions-session-redis` 把 AgentScope 会话状态存到 Redis。统一抽象出 `RedisClientAdapter`，支持 **Jedis、Lettuce、Redisson** 三个客户端，覆盖 Standalone、Cluster、Sentinel 等部署模式。

## 添加依赖

```xml
<dependency>
    <groupId>io.agentscope</groupId>
    <artifactId>agentscope-extensions-session-redis</artifactId>
    <version>${agentscope.version}</version>
</dependency>
```

模块本身不强制依赖某一客户端，按你项目里实际用的引入即可（Jedis / Lettuce / Redisson）。

## 快速上手（Lettuce 单机）

```java
import io.lettuce.core.RedisClient;
import io.agentscope.core.session.Session;
import io.agentscope.core.session.redis.RedisSession;

RedisClient redisClient = RedisClient.create("redis://localhost:6379");

Session session = RedisSession.builder()
    .lettuceClient(redisClient)
    .build();
```

## 三种客户端的接入

### Jedis

```java
import redis.clients.jedis.UnifiedJedis;

UnifiedJedis jedis = new redis.clients.jedis.JedisPooled("localhost", 6379);
Session session = RedisSession.builder()
    .jedisClient(jedis)   // UnifiedJedis、JedisCluster、JedisSentineled 都可
    .build();
```

### Lettuce 集群

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

> Redisson 还支持 `useClusterServers()` / `useSentinelServers()` / `useMasterSlaveServers()`，配好后照样传给 `redissonClient(...)`。

## 自定义 key 前缀

默认所有 key 形如 `agentscope:session:{sessionId}:...`。多个项目共享同一个 Redis 时建议自定义：

```java
Session session = RedisSession.builder()
    .lettuceClient(redisClient)
    .keyPrefix("myapp:session:")
    .build();
```

## Key 结构

| 类型 | Key 模式 |
| --- | --- |
| 单值 | `{prefix}{sessionId}:{stateKey}`（Redis String，存 JSON） |
| 列表 | `{prefix}{sessionId}:{stateKey}:list`（Redis List，每项一条 JSON） |
| 列表 hash | `{prefix}{sessionId}:{stateKey}:list:_hash`（变更检测用） |
| Session 索引 | `{prefix}{sessionId}:_keys`（Redis Set，记录该 session 下所有 stateKey） |

`_keys` 索引让 `delete(sessionKey)`、`exists(sessionKey)` 都只需要常数次 Redis 调用，避免 `KEYS *`。

## 与 SessionManager 整合

```java
SessionManager manager = SessionManager.builder()
    .session(session)
    .build();
```

之后你的 Memory、Workspace、Plan 等组件就会自动通过 Redis 持久化。

## 自定义客户端适配

如果你需要接其他 Redis 兼容存储（比如 KeyDB、Tair），可以实现 `RedisClientAdapter` 然后通过 `clientAdapter(...)` 注入：

```java
Session session = RedisSession.builder()
    .clientAdapter(new MyCustomAdapter(...))
    .build();
```

## Builder 配置参数

| 方法 | 说明 |
| --- | --- |
| `jedisClient(UnifiedJedis)` | Jedis 单机/集群/哨兵任意一种 |
| `lettuceClient(RedisClient)` | Lettuce 单机/Sentinel |
| `lettuceClusterClient(RedisClusterClient)` | Lettuce 集群 |
| `redissonClient(RedissonClient)` | Redisson 任意部署模式 |
| `clientAdapter(RedisClientAdapter)` | 自定义适配器 |
| `keyPrefix(String)` | 默认 `agentscope:session:` |

> 上述客户端方法互斥，只设置一个。
