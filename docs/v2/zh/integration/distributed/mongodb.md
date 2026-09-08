# MongoDB

`agentscope-extensions-mongodb` 提供全链路的 MongoDB 分布式存储实现，适合已部署 MongoDB 或偏好文档型后端的场景。

## 添加依赖

```xml
<dependency>
    <groupId>io.agentscope</groupId>
    <artifactId>agentscope-extensions-mongodb</artifactId>
    <version>${agentscope.version}</version>
</dependency>
```

模块依赖官方 MongoDB Java Driver，无需额外客户端库。

## 一键配置

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

通过连接字符串创建（store 管理客户端生命周期）：

```java
DistributedStore store = MongoDistributedStore.fromConnectionString(
        "mongodb://user:pass@host:27017/mydb?authSource=admin");
```

连接串中包含库名路径时（如 `/mydb`），将使用该库而非默认的 `agentscope`。

## 提供的组件

### 1. MongoAgentStateStore

Agent 会话状态持久化到 MongoDB。每个会话是一个文档，使用复合 `_id` `{user, session}`。State key 存储在 `states` 子文档中（通过 dot notation 访问），与保留字段隔离。

```java
import io.agentscope.extensions.mongodb.state.MongoAgentStateStore;

AgentStateStore store = MongoAgentStateStore.builder()
    .mongoClient(mongoClient)
    .databaseName("agentscope")
    .collectionName("agentscope_sessions")
    .build();
```

- **单值**：存储在 `states.<key>`，版本号在 `versions.<key>`。
- **列表**：存储在 `states.<key>` 为 BSON 数组，内容 hash 在 `hashes.<key>`，用于增量 append 优化。
- **TTL**：`_updated_at` 上 30 天过期（sparse 索引），与快照 TTL 对齐。

### 2. MongoBaseStore（BaseStore）

工作区文件系统 KV 存储，供 `RemoteFilesystemSpec` 使用。通过复合 `(namespace, key)` 索引上的范围查询实现前缀匹配——与 `InMemoryStore` 和 `PostgresBaseStore` 行为一致。

```java
import io.agentscope.extensions.mongodb.store.MongoBaseStore;

BaseStore store = new MongoBaseStore(mongoClient.getDatabase("agentscope"), "agentscope_base");
```

- 命名空间路径使用 `\u001F`（ASCII Unit Separator）作为段分隔符，带 trailing separator 实现前缀匹配。
- `put` / `putIfVersion` 通过 `version` 字段支持乐观并发。

### 3. MongoSnapshotSpec

沙箱快照存储为 BSON Binary，集合上建有 `createdAt` 的 30 天 TTL 索引。与会话 TTL 对齐，确保快照不会先于会话过期。

```java
import io.agentscope.extensions.mongodb.snapshot.MongoSnapshotSpec;

SandboxSnapshotSpec spec = new MongoSnapshotSpec(mongoClient, "agentscope");
```

> BSON 文档大小上限 16 MB，快照上限 15 MB。更大工作区建议混合后端（MongoDB 管状态和锁，OSS 管快照）。

### 4. MongoSandboxExecutionGuard

基于 MongoDB 集合的分布式锁，`expiresAt` 上建有 TTL 索引。每次获取锁生成随机 UUID token 用于安全续约和释放——同一 JVM 内两个 guard 实例不会误删对方的锁。

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

- `lockTimeout` 仅限制获取等待时间；`leaseTtl` 控制锁文档生存期。
- 后台 watchdog 每 `leaseTtl / 3` 续约一次，使用 2 线程池避免单线程阻塞。
- guard 实现 `AutoCloseable`，由 `MongoDistributedStore.close()` 级联关闭。

## 集合与 TTL

| 集合 | 用途 | TTL | 默认名称 |
|------|------|-----|---------|
| 会话 | Agent 状态存储 | `_updated_at` 30 天 | `agentscope_sessions` |
| 基础存储 | 工作区 KV | — | `agentscope_base` |
| 快照 | 沙箱 tar 归档 | `createdAt` 30 天 | `agentscope_snapshots` |
| 沙箱锁 | 分布式锁 | `expiresAt` 即时 | `agentscope_sandbox_locks` |

所有集合名集中在 `MongoConstants` 中管理，可按组件覆盖。

## 选型建议

| 场景 | 建议 |
|------|------|
| 已有 MongoDB 集群 | **首选** MongoDB |
| 偏好文档型存储 | MongoDB |
| 大量会话历史（字段无长度限制） | MongoDB（无 MySQL 字段长度红线） |
| 大工作区快照（>15 MB） | 混合后端：MongoDB 管状态和锁，OSS 管快照 |
