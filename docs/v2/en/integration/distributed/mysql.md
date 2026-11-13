---
title: MySQL / JDBC
zh_link: /v2/zh/integration/distributed/mysql
---

`agentscope-extensions-mysql` provides full-stack JDBC-based distributed storage for teams with existing relational database infrastructure.

## Dependency

```xml
<dependency>
    <groupId>io.agentscope</groupId>
    <artifactId>agentscope-extensions-mysql</artifactId>
    <version>${agentscope.version}</version>
</dependency>
```

Add your database driver separately (e.g. `mysql-connector-j`, `postgresql`).

## One-Line Setup

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

## Components Provided

### 1. MysqlAgentStateStore

Agent state persisted to a MySQL table.

```java
import io.agentscope.extensions.mysql.state.MysqlAgentStateStore;

AgentStateStore store = new MysqlAgentStateStore(dataSource, true);  // auto-create schema
AgentStateStore store = new MysqlAgentStateStore(
    dataSource, "agentscope_prod", "session_state", true);  // custom DB/table names
```

### 2. JdbcStore (BaseStore)

Workspace filesystem KV storage with auto-detected dialect.

```java
import io.agentscope.extensions.mysql.store.JdbcStore;

BaseStore store = JdbcStore.builder(dataSource)
    .initializeSchema(true)
    .build();
```

Supported dialects (auto-detected): MySQL, PostgreSQL, H2, SQLite.

New MySQL tables use `utf8mb4_bin` for namespace, item, session, state and snapshot keys. Existing tables are not altered automatically: follow the [SQL migration instructions](/v2/en/integration/session/mysql#migrating-tables-created-before-this-change) before relying on case-sensitive keys. Migration changes `=` and prefix `LIKE` comparisons; previously overwritten case-variant keys cannot be recovered.

#### Long workspace and session keys (unified JDBC store)

For `io.agentscope.extensions.jdbc.store.JdbcStore` in `agentscope-extensions-jdbc`, new PostgreSQL and H2 tables use `item_key VARCHAR(2048)` to accommodate workspace and session transcript paths. MySQL remains at 255 characters to keep its composite primary key within the InnoDB utf8mb4 index limit. The legacy `io.agentscope.extensions.mysql.store.JdbcStore` retains its original new-table lengths.

Existing tables are never altered automatically. Back up the database and run the matching migration during a maintenance window (replace the table name or qualify it with your schema if configured):

```sql
-- PostgreSQL
ALTER TABLE agentscope_store ALTER COLUMN item_key TYPE VARCHAR(2048);

-- H2
ALTER TABLE agentscope_store ALTER COLUMN item_key VARCHAR(2048);
```

Run only the statement for your database; do not apply this widening to MySQL. Automatic schema checks do not migrate existing column lengths.

### 3. JdbcSnapshotSpec

Sandbox snapshots as LONGBLOB in a database table.

```java
import io.agentscope.extensions.mysql.snapshot.JdbcSnapshotSpec;

SandboxSnapshotSpec spec = new JdbcSnapshotSpec(dataSource);
```

### 4. JdbcSandboxExecutionGuard

Distributed lock via MySQL `GET_LOCK()` / `RELEASE_LOCK()`.

```java
import io.agentscope.extensions.mysql.sandbox.JdbcSandboxExecutionGuard;

SandboxExecutionGuard guard = JdbcSandboxExecutionGuard.builder(dataSource)
    .keyPrefix("myapp:lock:")
    .lockTimeout(Duration.ofMinutes(30))
    .build();
```

Lock is tied to the JDBC connection — auto-released on connection close.

## When to Use

| Scenario | Recommendation |
|----------|---------------|
| Existing MySQL, don't want Redis | **First choice**: MySQL |
| Need SQL audit / reporting / joins | MySQL |
| Large snapshots (>100MB) | MySQL BLOB works but consider OSS |
| Lowest latency | Redis |
