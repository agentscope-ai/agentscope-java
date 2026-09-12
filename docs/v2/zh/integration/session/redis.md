---
title: Redis 状态存储
---

<Note>

本页面内容已迁移至 [分布式存储 — Redis](/v2/zh/integration/distributed/redis)。以下内容保留作为参考，但建议使用新文档。

</Note>


# Redis 状态存储

`agentscope-extensions-redis` 把 AgentScope 的 Agent 状态存到 Redis。统一抽象出 `RedisClientAdapter`，支持 **Jedis、Lettuce、Redisson** 三个客户端，覆盖 Standalone、Cluster、Sentinel 等部署模式。

## 添加依赖

```xml
<dependency>
    <groupId>io.agentscope</groupId>
    <artifactId>agentscope-extensions-redis</artifactId>
    <version>${agentscope.version}</version>
</dependency>
```

按项目需要选择 Jedis、Lettuce 或 Redisson。Redisson 状态存储集成要求使用 **Redisson 4.x API**（`RScript.ReturnType.LONG`），不兼容 Redisson 3.x。AgentScope 当前管理并测试的版本为 **4.2.0**。例如，可将直接引入的 Redisson 依赖对齐到该版本：

```xml
<dependency>
    <groupId>org.redisson</groupId>
    <artifactId>redisson</artifactId>
    <version>4.2.0</version>
</dependency>
```

如果自行管理 Redisson Spring Boot starter，3.x starter（例如 3.52.0）与此状态存储集成不兼容。请将 starter 及其 Redisson 依赖对齐到当前的 4.2.0 基线，并检查最终解析出的运行时依赖。当加载的 API 缺少 `LONG` 时，`RedisAgentStateStore.builder().redissonClient(...)` 和已弃用的 `RedissonAgentStateStore` 都会在构造阶段报错。此检查仅报告兼容性问题，不会修复依赖解析，也不会让 Redisson 3.x 支持状态持久化。

## 快速上手（Lettuce 单机）

```java
import io.lettuce.core.RedisClient;
import io.agentscope.core.state.AgentStateStore;
import io.agentscope.extensions.redis.state.RedisAgentStateStore;

RedisClient redisClient = RedisClient.create("redis://localhost:6379");

AgentStateStore stateStore = RedisAgentStateStore.builder()
    .lettuceClient(redisClient)
    .build();
```

## 三种客户端的接入

### Jedis

```java
import redis.clients.jedis.UnifiedJedis;

UnifiedJedis jedis = new redis.clients.jedis.JedisPooled("localhost", 6379);
AgentStateStore stateStore = RedisAgentStateStore.builder()
    .jedisClient(jedis)   // UnifiedJedis、JedisCluster、JedisSentineled 都可
    .build();
```

### Lettuce 集群

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

> Redisson 还支持 `useClusterServers()` / `useSentinelServers()` / `useMasterSlaveServers()`，配好后照样传给 `redissonClient(...)`。

## 自定义 key 前缀

默认所有 key 形如 `agentscope:session:{userSegment}/{sessionId}:...`（`userSegment` 即 `userId`，匿名 session 用 `__anon__`）。多个项目共享同一个 Redis 时建议自定义：

```java
AgentStateStore stateStore = RedisAgentStateStore.builder()
    .lettuceClient(redisClient)
    .keyPrefix("myapp:session:")
    .build();
```

## Key 结构

`(userId, sessionId)` 二元组会被打包成单一槽位标识 `{userSegment}/{sessionId}`（`userSegment` 为 `userId`，`userId` 为 null 时取 `__anon__`）。

| 类型 | Key 模式 |
| --- | --- |
| 单值 | `{prefix}{userSegment}/{sessionId}:{stateKey}`（Redis String，存 JSON） |
| 列表 | `{prefix}{userSegment}/{sessionId}:{stateKey}:list`（Redis List，每项一条 JSON） |
| 列表 hash | `{prefix}{userSegment}/{sessionId}:{stateKey}:list:_hash`（变更检测用） |
| Session 索引 | `{prefix}{userSegment}/{sessionId}:_keys`（Redis Set，记录该 session 下所有 stateKey） |

`_keys` 索引让 `delete(userId, sessionId)`、`exists(userId, sessionId)` 都只需要常数次 Redis 调用，避免 `KEYS *`。

## 挂载到 Agent

```java
ReActAgent agent = ReActAgent.builder()
    .name("assistant")
    .model(model)
    .stateStore(stateStore)
    .build();
```

之后你的 Memory、Workspace、Plan 等组件就会自动通过 Redis 持久化。每次调用读写哪个槽位，由该次调用的 `RuntimeContext` 决定：

```java
RuntimeContext rc = RuntimeContext.builder()
    .userId("alice")
    .sessionId("session-1")
    .build();

agent.call(msg, rc).block();
```

## 自定义客户端适配

如果你需要接其他 Redis 兼容存储（比如 KeyDB、Tair），可以实现 `RedisClientAdapter` 然后通过 `clientAdapter(...)` 注入：

```java
AgentStateStore stateStore = RedisAgentStateStore.builder()
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
