---
title: MySQL / JDBC
en_link: /v2/en/integration/distributed/mysql
---

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
    .filesystem(new RemoteFilesystemSpec()
            .isolationScope(IsolationScope.USER))
    .build();
```

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

新建 MySQL 表的命名空间、条目、会话、状态和快照键使用 `utf8mb4_bin`。已有表不会自动修改：依赖大小写敏感键之前，请按 [MySQL 状态存储中的 SQL 迁移说明](/v2/zh/integration/session/mysql) 手动迁移。迁移会改变 `=` 和前缀 `LIKE` 的比较语义；此前被覆盖的大小写变体键无法恢复。

#### 长工作区与会话键（统一 JDBC 存储）

`agentscope-extensions-jdbc` 中的 `io.agentscope.extensions.jdbc.store.JdbcStore` 在 PostgreSQL 和 H2 新建表时使用 `item_key VARCHAR(2048)`，以容纳工作区和会话转录路径。MySQL 保持 255 字符，避免联合主键超出 InnoDB utf8mb4 索引上限。legacy 的 `io.agentscope.extensions.mysql.store.JdbcStore` 仍保留原有的新建表长度。

已有表不会自动修改。请先备份数据库，在维护窗口执行对应迁移（如配置了表名或 schema，请替换或限定表名）：

```sql
-- PostgreSQL
ALTER TABLE agentscope_store ALTER COLUMN item_key TYPE VARCHAR(2048);

-- H2
ALTER TABLE agentscope_store ALTER COLUMN item_key VARCHAR(2048);
```

仅执行与你的数据库对应的语句，不要将此扩容应用于 MySQL。自动表结构检查不会迁移已有列长度。

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
