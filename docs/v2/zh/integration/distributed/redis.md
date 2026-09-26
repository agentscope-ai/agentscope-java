---
title: Redis
en_link: /v2/en/integration/distributed/redis
---

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

使用 Jedis Cluster 时，需要显式选择支持 Redis Cluster 的 Agent State key 布局：

```java
import io.agentscope.extensions.redis.state.RedisAgentStateStore;

DistributedStore store = RedisDistributedStore.fromJedis(
    jedisCluster,
    "prod:",
    RedisAgentStateStore.KeyLayoutVersion.V1);
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
    .keyLayoutVersion(RedisAgentStateStore.KeyLayoutVersion.V1)
    .build();

// Redisson
Config config = new Config();
config.useSingleServer().setAddress("redis://localhost:6379");
AgentStateStore store = RedisAgentStateStore.builder()
    .redissonClient(Redisson.create(config))
    .build();
```

#### V0 与 V1 key 布局

`RedisAgentStateStore` 默认使用 V0，以兼容已有 Redis key。Redis Cluster 必须显式选择 V1，
同一个 key 前缀下的所有应用实例也必须使用相同版本。

假设配置和调用参数如下：

```text
keyPrefix = agentscope:session:
userId = alice
sessionId = s1
```

两种布局只在 session 段上有差异：V0 使用 `alice/s1`，V1 则使用 Redis hash tag
`{alice/s1}`。

| 数据 | V0 key | V1 key |
|---|---|---|
| 单值状态 `agent_state` | `agentscope:session:alice/s1:agent_state` | `agentscope:session:{alice/s1}:agent_state` |
| 状态版本 | `agentscope:session:alice/s1:agent_state:ver` | `agentscope:session:{alice/s1}:agent_state:ver` |
| 列表状态 `messages` | `agentscope:session:alice/s1:messages:list` | `agentscope:session:{alice/s1}:messages:list` |
| 列表变更检测 hash | `agentscope:session:alice/s1:messages:list:_hash` | `agentscope:session:{alice/s1}:messages:list:_hash` |
| Session 索引 | `agentscope:session:alice/s1:_keys` | `agentscope:session:{alice/s1}:_keys` |

Session 索引是一个 Redis Set，其中的成员格式不随布局改变。单值状态对应
`agent_state`，列表状态对应 `messages:list`。`userId` 为 null 或空白字符串时，两种布局
都会将它转换为 `__anon__`。

V0 没有 hash tag，Redis Cluster 会按完整 key 分别计算 slot。乐观并发 Lua 脚本用到的
状态 key、版本 key 和 Session 索引可能落在不同 slot，进而触发 `CROSSSLOT`。V1 只对
`{...}` 中的 `alice/s1` 计算 slot，因此同一 session 下的 key 会落在同一个 slot。

V0 和 V1 使用不同的 key namespace。Store 配置为某个布局后，读写、`exists`、`delete`
和 `listSessionIds` 都只操作该布局，不会读取另一个布局的数据。V1 还有以下限制：

- `keyPrefix` 不能包含 `{` 或 `}`。
- `userId` 和 `sessionId` 不能包含 `{`、`}` 或 `/`。
- `sessionId` 不能为 null 或空白字符串。

#### 从 V0 迁移到 V1

框架不会自动迁移数据，也不提供双写。推荐按 key 前缀停写迁移：

1. 盘点并校验。找出使用该前缀的所有应用实例，以及需要保留的
   `(userId, sessionId)`。先处理不满足 V1 字符限制的 ID。对于包含 `/` 的 V0 ID，不要
   直接拆分 Redis key 来推断原始 ID，应以应用保存的 ID 为准。
2. 确定是否保留旧状态。如果 Agent 状态可以丢弃，或者已有较短 TTL，可以停写后删除
   或等待 V0 key 过期，然后直接启用 V1。需要保留状态时，继续执行后面的复制步骤。
3. 停止写入并备份 Redis。等待在途请求结束，禁止所有实例继续写入该前缀，然后创建
   备份或快照。边写边复制可能导致状态值、版本号和 Session 索引不一致。
4. 按 session 逐 key 复制。只把 session 段从 `userId/sessionId` 改为
   `{userId/sessionId}`。复制所有现存 key，包括单值状态、版本、列表、列表 hash 和
   `_keys`。最后复制 `_keys`，避免未复制完成的 session 被识别为已存在。
5. 保留 Redis 类型和 TTL。迁移工具可以针对每个 key 分别执行 `DUMP`、`PTTL` 和
   `RESTORE ... REPLACE`。`PTTL = -1` 表示目标 key 不过期，此时 `RESTORE` 的 TTL 使用
   `0`；`PTTL = -2` 表示源 key 已不存在，应跳过。使用支持 Cluster 路由的客户端，确保
   源 key 和目标 key 的命令发送到正确节点。不要跨 slot 使用 `RENAME`，也不要使用多 key
   复制或删除命令。
6. 切换前校验。检查 V0 和 V1 的 Redis 类型、值、TTL、版本号、列表内容和 `_keys`
   成员，并核对 session 总数。对 V1 的状态 key、版本 key 和 Session 索引执行
   `CLUSTER KEYSLOT`，三者的 slot 必须相同。再用测试 session 验证加载、保存、CAS、
   列表和删除操作。
7. 同时切换所有实例。让使用该前缀的 Store 全部改为 `KeyLayoutVersion.V1`，完成统一
   发布后再恢复流量。V0 和 V1 写入实例同时运行，会让同一个 session 产生两份独立变化
   的数据。
8. 在观察期保留 V0 数据。切换到 V1 后不要再修改这份 V0 副本。如果 V1 尚未产生新
   写入，可以让所有实例直接切回 V0。V1 已经产生新写入时，需要再次停写，将变化的数据
   反向迁移后再回滚，否则 V0 看不到这些更新。切回 V0 也会重新引入 Redis Cluster 的
   `CROSSSLOT` 问题。
9. 定向清理 V0 数据。观察期结束后，逐 key 删除旧的 V0 数据。不要用
   `clearAllSessions()` 完成这一步，因为它会删除该前缀下的 V0 和 V1 key。


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
