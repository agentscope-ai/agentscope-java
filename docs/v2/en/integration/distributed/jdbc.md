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

> The legacy modules `agentscope-extensions-mysql`, `agentscope-extensions-postgresql`, `agentscope-extensions-skill-mysql-repository`, and `agentscope-extensions-skill-postgresql-repository` are deprecated and replaced by this module. See [migration](#migrating-from-legacy-modules) below.

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

Table creation and schema validation happen once, at assembly in `AbstractJdbcDialect.from(ds).build()`: `autoCreateTable` (default true) controls whether DDL is executed, and all enabled business tables are validated either way — a missing table or column fails fast with the reference DDL (in Spring this surfaces at context startup). Table names and prefixes must match `[A-Za-z_][A-Za-z0-9_]*`.

**Table groups**: `enableBaseTables` (default on) and `enableSkillTables` (default off) decide which groups `build()` creates and validates. They are independent of `autoCreateTable` and of each other — base tables on MySQL with skills on the git channel is a valid setup.

```java
AbstractJdbcDialect dialect = AbstractJdbcDialect.from(dataSource)
    .enableBaseTables(true)      // default: on — store, sessions, snapshots
    .enableSkillTables(true)     // default: off — opt in to add the skill tables
    .build();                    // creates agentscope_skills / agentscope_skill_resources
```

Tables created by default: `agentscope_store`, `agentscope_sessions`, `agentscope_snapshots`; the lock table `agentscope_distributed_locks` is created on first lock use. With the skill group enabled: additionally `agentscope_skills`, `agentscope_skill_resources`.

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

### 5. JdbcAgentSkillRepository

Skill storage on the same dialects: implements core's `AgentSkillRepository`, so every database this module supports can back the skill channel.

```java
import io.agentscope.core.skill.repository.AgentSkillRepository;
import io.agentscope.extensions.jdbc.skill.JdbcAgentSkillRepository;

AbstractJdbcDialect dialect = AbstractJdbcDialect.from(dataSource)
    .enableSkillTables(true)
    .build();

AgentSkillRepository repo = new JdbcAgentSkillRepository(dataSource, dialect);
```

Two tables are created: `agentscope_skills` and `agentscope_skill_resources` (composite PK `(id, resource_path)`, foreign key with `ON DELETE CASCADE`). Like the other components, the repository never touches the schema — enable the group at build; constructing it over a dialect without the group fails fast.

Two behaviors to note:

- `metadata_json` is a required column. A table from before the column existed fails startup validation with the reference DDL; add the column as the error suggests and restart — the framework never alters existing tables.
- `delete` removes a skill's resources explicitly before the row itself, so it behaves the same on SQLite, where the cascade only fires with `PRAGMA foreign_keys` on. Resource paths must be relative without `..` — anything escaping the skill directory is rejected on save, and rows read back from the table are validated the same way.

## Migrating from Legacy Modules

Continued use of the legacy modules is discouraged — migrate as early as your schedule allows:

| Deprecated | Replacement |
|------------|-------------|
| `MysqlDistributedStore.create(ds)` / `PostgresDistributedStore.create(ds)` | `JdbcDistributedStore.create(ds)` |
| `new MysqlAgentStateStore(ds)` | `new JdbcAgentStateStore(ds, AbstractJdbcDialect.from(ds).build())` |
| `JdbcStore.builder(ds).dialect(mysqlDialect)` | `JdbcStore.builder(ds).dialect(AbstractJdbcDialect.from(ds).build())` |
| `MysqlSkillRepository` / `PostgresSkillRepository` (skill-mysql / skill-postgresql modules) | `new JdbcAgentSkillRepository(ds, AbstractJdbcDialect.from(ds).enableSkillTables(true).build())` |

Migrating the skill repositories:

- Tables created by the current legacy modules already include `metadata_json` and work as-is; older tables need the column added first — the startup error carries the reference DDL.
- One caveat to "work as-is": the legacy modules never rejected absolute or `..` resource paths, and the new implementation validates rows on read — `getSkill` refuses such a row; `getAllSkills` / `getAllSkillNames` skip it with a warning. A row whose *name* fails validation cannot be deleted either — `clearAllSkills` or direct SQL is the only remedy. Audit `resource_path` and skill names before migrating; those values were never safely consumable downstream.
- The old modules implicitly created an `agentscope` database (MySQL) or schema (PostgreSQL). The new repository puts its tables wherever the connection points — aim the `DataSource` at the existing tables.
- `databaseName` / `schemaName` have no equivalent — the tables live in whatever database the DataSource points to, same as the base tables. Table names can be overridden via `skillTableName` / `skillResourcesTableName`. Correspondingly, `getSource()` changes from `mysql_<databaseName>_<table>` / `postgresql_<schemaName>_<table>` to `jdbc_<skillTableName>` — consumers keying on it (e.g. the skill staging cache namespace) get a fresh subtree after migration, and the old one is reclaimed by orphan GC.

## When to Use

| Scenario | Recommendation |
|----------|----------------|
| Existing relational database, don't want Redis | **First choice**: JDBC module |
| Need SQL audit / reporting / joins | JDBC module |
| Large snapshots (>100MB) | BLOB works but consider OSS |
| Lowest latency | Redis |
