# MySQL / JDBC

`agentscope-extensions-mysql` 提供基于 JDBC 的全链路分布式存储实现，适合已有关系型数据库基础设施的场景。

## 添加依赖

```xml
<dependency>
    <groupId>io.agentscope</groupId>
    <artifactId>agentscope-extensions-mysql</artifactId>
    <version>${agentscope.version}</version>
</dependency>
```

数据库驱动按实际使用的版本自行引入（如 `mysql-connector-j`、`postgresql`）。

## 一键配置

```java
import io.agentscope.extensions.mysql.MysqlDistributedStore;

DataSource dataSource = ...;  // HikariCP, Druid, etc.
DistributedStore store = MysqlDistributedStore.create(dataSource);

HarnessAgent agent = HarnessAgent.builder()
    .distributedStore(store)
    .maxPersistedContextMessages(200)  // 可选：限制会话状态增长
    .filesystem(new RemoteFilesystemSpec()
            .isolationScope(IsolationScope.USER))
    .build();
```

`agent_state` 会序列化完整的会话上下文，因此长期运行的会话会持续增长。请参阅 [Agent 状态存储](../session/overview.md)，了解持久化消息上限和由接入系统主动删除会话的方式。

## 提供的组件

### 1. MysqlAgentStateStore

Agent 状态持久化到 MySQL 表。

```java
import io.agentscope.extensions.mysql.state.MysqlAgentStateStore;

// 自动建库建表
AgentStateStore store = new MysqlAgentStateStore(dataSource, true);

// 自定义库名 / 表名
AgentStateStore store = new MysqlAgentStateStore(
    dataSource, "agentscope_prod", "session_state", true);
```

**表结构**：自动创建的表包含 `user_id`、`session_id`、`state_key`、`state_value`（LONGTEXT JSON）、`state_type`、`updated_at` 等列。库名 / 表名仅允许 `[a-zA-Z_][a-zA-Z0-9_-]*`，长度 ≤ 64。

### 2. JdbcStore（BaseStore）

工作区文件系统 KV 存储，支持多种数据库方言。

```java
import io.agentscope.extensions.mysql.store.JdbcStore;

BaseStore store = JdbcStore.builder(dataSource)
    .initializeSchema(true)    // 自动建表
    .tableName("agentscope_store")  // 可选自定义表名
    .build();
```

**支持的方言**（自动检测）：

| 数据库 | 方言类 |
|--------|--------|
| MySQL / MariaDB | `MysqlJdbcStoreDialect` |
| PostgreSQL | `PostgresJdbcStoreDialect` |
| H2 | `H2JdbcStoreDialect` |
| SQLite | `SqliteJdbcStoreDialect` |

**并发安全**：`putIfVersion` 通过单语句 CAS `UPDATE ... WHERE version = ?` 实现。

### 3. JdbcSnapshotSpec

沙箱快照以 LONGBLOB 存储到数据库表。

```java
import io.agentscope.extensions.mysql.snapshot.JdbcSnapshotSpec;

SandboxSnapshotSpec spec = new JdbcSnapshotSpec(dataSource);
SandboxSnapshotSpec spec = new JdbcSnapshotSpec(dataSource, "custom_snapshots");
```

表结构：`snapshot_id VARCHAR(512) PK`、`data LONGBLOB`、`created_at TIMESTAMP`。自动建表。

### 4. JdbcSandboxExecutionGuard

基于 MySQL `GET_LOCK()` / `RELEASE_LOCK()` 的分布式锁。

```java
import io.agentscope.extensions.mysql.sandbox.JdbcSandboxExecutionGuard;

SandboxExecutionGuard guard = JdbcSandboxExecutionGuard.builder(dataSource)
    .keyPrefix("myapp:lock:")
    .lockTimeout(Duration.ofMinutes(30))
    .build();
```

锁绑定 JDBC 连接——连接关闭时自动释放。lock name 超过 64 字符时自动 hash。

> 注意：MySQL named locks 是 server 级别的（非 database 级别）。在共享 MySQL 实例时，使用唯一的 `keyPrefix` 避免冲突。

## 选型建议

| 场景 | 建议 |
|------|------|
| 已有 MySQL，不想引入 Redis | **首选** MySQL |
| 需要 SQL 审计 / 报表 / 联表查询 | MySQL |
| 快照数据量大（>100MB） | MySQL BLOB 可行但推荐 OSS |
| 追求最低延迟 | Redis |
