# Redis

`agentscope-extensions-redis` 提供全链路的 Redis 分布式存储实现，是多副本生产部署的首选后端。

## 添加依赖

```xml
<dependency>
    <groupId>io.agentscope</groupId>
    <artifactId>agentscope-extensions-redis</artifactId>
    <version>${agentscope.version}</version>
</dependency>
```

模块本身不强制依赖某一 Redis 客户端，按项目实际使用引入（Jedis / Lettuce / Redisson）。

## 一键配置

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

自定义 key 前缀（多环境隔离）：

```java
DistributedStore store = RedisDistributedStore.fromJedis(jedis, "prod:");
```

## 提供的组件

### 1. RedisAgentStateStore

Agent 状态持久化到 Redis。支持 Jedis / Lettuce / Redisson 三种客户端。

```java
import io.agentscope.extensions.redis.state.RedisAgentStateStore;

// Jedis
AgentStateStore store = RedisAgentStateStore.builder()
    .jedisClient(new JedisPooled("redis://localhost:6379"))
    .keyPrefix("myapp:session:")
    .build();

// Lettuce 集群
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

**存储结构**：
- 单值：`{prefix}{userId}/{sessionId}:{stateKey}` — Redis String（JSON）
- 列表：`{prefix}{userId}/{sessionId}:{stateKey}:list` — Redis List（JSON items）
- 增量写入：通过 hash 摘要 + 计数比较，仅 append 新增项

### 2. RedisStore（BaseStore）

工作区文件系统 KV 存储，供 `RemoteFilesystemSpec` 使用。

```java
import io.agentscope.extensions.redis.store.RedisStore;

BaseStore store = new RedisStore(jedis);
BaseStore store = new RedisStore(jedis, "myapp:store:");
```

**并发安全**：`put` / `putIfVersion` 使用 Lua 脚本保证原子性（version read + hash write + index update 单次 `EVAL`），`putIfVersion` 可作为分布式 CAS 原语。

### 3. RedisSnapshotSpec

沙箱快照存储到 Redis 二进制 key。适合小工作区 + 短 TTL 场景。

```java
import io.agentscope.extensions.redis.snapshot.RedisSnapshotSpec;

SandboxSnapshotSpec spec = new RedisSnapshotSpec(jedis, "myapp:snapshot:", 3600);
```

> 注意 Redis 内存代价——大工作区（>50MB）建议用 OSS。

### 4. RedisSandboxExecutionGuard

基于 Redis `SET NX PX` 租约的分布式锁，用于 `AGENT` / `GLOBAL` 隔离范围下的多副本并发控制。

```java
import io.agentscope.extensions.redis.sandbox.RedisSandboxExecutionGuard;

SandboxExecutionGuard guard = RedisSandboxExecutionGuard.builder(jedis)
    .keyPrefix("myapp:guard:")
    .leaseTtl(Duration.ofMinutes(30))
    .retryInterval(Duration.ofMillis(500))
    .build();
```

## 选型建议

| 场景 | 建议 |
|------|------|
| 多副本生产，追求低延迟 | **首选** Redis |
| 已有 Redis 集群 | Lettuce Cluster 或 Redisson Sentinel |
| 小工作区 + 短 TTL 快照 | Redis 快照可以，但注意内存 |
| 大工作区快照 | 混合后端：Redis 管状态和锁，OSS 管快照 |
