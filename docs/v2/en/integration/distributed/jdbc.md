---
title: JDBC
zh_link: /v2/zh/integration/distributed/jdbc
---

`agentscope-extensions-jdbc` provides full-stack distributed storage over standard JDBC and is the single entry point for relational databases: pass any JDBC `DataSource` and the dialect is auto-detected via SPI — no per-database setup. A natural fit for teams with existing relational database infrastructure.

Currently supported databases:

| Database | Role |
|----------|------|
| MySQL | Common production choice |
| PostgreSQL | Common production choice |
| H2 | In-memory / embedded, testing and development |
| SQLite | Embedded, lightweight single-node scenarios |

Support for more relational databases is on the roadmap, including Oracle and domestic Chinese databases such as DM (Dameng), GaussDB, and OceanBase.

> The legacy modules `agentscope-extensions-mysql` and `agentscope-extensions-postgresql` are deprecated and replaced by this module. See [migration](#migrating-from-legacy-modules) below.

## Dependency

```xml
<dependency>
    <groupId>io.agentscope</groupId>
    <artifactId>agentscope-extensions-jdbc</artifactId>
    <version>${agentscope.version}</version>
</dependency>
```

Add your database driver separately (e.g. `mysql-connector-j`, `postgresql`, `sqlite-jdbc`).

## One-Line Setup

```java
import io.agentscope.extensions.jdbc.JdbcDistributedStore;

DataSource dataSource = ...;  // HikariCP, Druid, etc.
DistributedStore store = JdbcDistributedStore.create(dataSource);

HarnessAgent agent = HarnessAgent.builder()
    .distributedStore(store)
    .filesystem(new RemoteFilesystemSpec()
            .isolationScope(IsolationScope.USER))
    .build();
```

For table-name customization, setting a shared `tablePrefix` is recommended. The builder also offers `storeTableName` / `sessionStateTableName` / `snapshotTableName` for full per-table overrides, which are rarely needed:

```java
AbstractJdbcDialect dialect = AbstractJdbcDialect.from(dataSource)
    .tablePrefix("myapp_")            // default: agentscope_
    .build();

DistributedStore store = JdbcDistributedStore.create(dataSource, dialect);
```

Table creation and schema validation happen once, at assembly in `AbstractJdbcDialect.from(ds).build()`: `autoCreateTable` (default true) controls whether DDL is executed, and all three business tables are validated either way — a missing table or column fails fast with the reference DDL (in Spring this surfaces at context startup). Tables created by default: `agentscope_store`, `agentscope_sessions`, `agentscope_snapshots`, `agentscope_distributed_locks`. Table names and prefixes must match `[A-Za-z_][A-Za-z0-9_]*`.

## Components Provided

### 1. JdbcAgentStateStore

Agent state persisted to a database table.

```java
import io.agentscope.extensions.jdbc.dialect.AbstractJdbcDialect;
import io.agentscope.extensions.jdbc.state.JdbcAgentStateStore;

AbstractJdbcDialect dialect = AbstractJdbcDialect.from(dataSource).build();  // schema creation + validation happen here

AgentStateStore store = new JdbcAgentStateStore(dataSource, dialect);
```

**Schema**: the auto-created table has columns `session_id`, `state_key`, `item_index`, `state_data` (LONGTEXT JSON), `version`, `created_at`, `updated_at`, with primary key `(session_id, state_key, item_index)`. The `version` column backs `saveIfVersion` optimistic concurrency control (CAS writes).

### 2. JdbcStore (BaseStore)

Workspace filesystem KV storage. All SQL comes from the dialect layer — the component itself is database-agnostic.

```java
import io.agentscope.extensions.jdbc.store.JdbcStore;

BaseStore store = JdbcStore.builder(dataSource)
    .dialect(dialect)
    .build();
```

**Concurrency**: `putIfVersion` uses a single-statement CAS `UPDATE ... WHERE version = ?`, supported on all databases above.

### 3. JdbcSnapshotSpec

Sandbox snapshots stored as BLOBs (LONGBLOB on MySQL, BYTEA on PostgreSQL).

```java
import io.agentscope.extensions.jdbc.snapshot.JdbcSnapshotSpec;

SandboxSnapshotSpec spec = new JdbcSnapshotSpec(dataSource, dialect);
```

### 4. JdbcSandboxExecutionGuard

Distributed lock; the lock strategy is decided by the dialect:

- **MySQL**: native `GET_LOCK()` / `RELEASE_LOCK()`. The lock is tied to the JDBC connection and auto-released on connection close; lock names longer than 64 characters are hashed automatically.
- **PostgreSQL / H2 / SQLite**: a portable lock on the `agentscope_distributed_locks` table, with no database-specific syntax.

```java
import io.agentscope.extensions.jdbc.sandbox.JdbcSandboxExecutionGuard;

SandboxExecutionGuard guard = JdbcSandboxExecutionGuard.builder(dialect)
    .keyPrefix("myapp:lock:")
    .lockTimeout(Duration.ofMinutes(30))
    .build();
```

> Note: MySQL named locks are server-level, not database-level. Use a unique `keyPrefix` when sharing a MySQL instance.

## Migrating from Legacy Modules

Continued use of the legacy modules is discouraged — migrate as early as your schedule allows:

| Deprecated | Replacement |
|------------|-------------|
| `MysqlDistributedStore.create(ds)` / `PostgresDistributedStore.create(ds)` | `JdbcDistributedStore.create(ds)` |
| `new MysqlAgentStateStore(ds)` | `new JdbcAgentStateStore(ds, AbstractJdbcDialect.from(ds).build())` |
| `JdbcStore.builder(ds).dialect(mysqlDialect)` | `JdbcStore.builder(ds).dialect(AbstractJdbcDialect.from(ds).build())` |

## When to Use

| Scenario | Recommendation |
|----------|----------------|
| Existing relational database, don't want Redis | **First choice**: JDBC module |
| Need SQL audit / reporting / joins | JDBC module |
| Large snapshots (>100MB) | BLOB works but consider OSS |
| Lowest latency | Redis |
