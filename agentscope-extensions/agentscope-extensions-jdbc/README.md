# agentscope-extensions-jdbc

Unified multi-database JDBC dialect abstraction for AgentScope Java. Replaces the
deprecated `agentscope-extensions-mysql` and `agentscope-extensions-postgresql`
modules with a single module that supports MySQL, PostgreSQL, H2, and SQLite
through one aggregated `JdbcDialect` interface.

## Quick Start

```java
DataSource dataSource = ... // HikariCP, Druid, etc.

HarnessAgent agent = HarnessAgent.builder()
    .name("my-agent")
    .model("dashscope:qwen-plus")
    .distributedStore(JdbcDistributedStore.create(dataSource))
    .filesystem(new DockerFilesystemSpec().image("ubuntu:24.04"))
    .build();
```

The dialect is auto-detected from the `DataSource`. No manual dialect selection
required.

## Architecture

Four layers, top-down:

1. **Facade** — `JdbcDistributedStore.create(DataSource)` auto-detects the dialect
   and assembles all four components.
2. **Component layer** (database-agnostic) — `JdbcStore`, `JdbcAgentStateStore`,
   `JdbcRemoteSnapshotClient`, `JdbcSandboxExecutionGuard`. Zero inline SQL.
3. **Dialect interface layer** — `JdbcDialect` extends `JdbcStoreDialect`,
   `AgentStateStoreDialect`, `SnapshotStoreDialect`. ANSI/PostgreSQL defaults;
   portable `TableBasedLockStrategy` fallback.
4. **Database implementation layer** — one class per database, overrides only
   what diverges from ANSI.

### Adding a New Database

Implement `JdbcDialect` in a single class. Override only the methods where your
database's SQL diverges from the ANSI/PostgreSQL defaults. The compiler enforces
coverage of all abstract methods.

```java
public class OracleDialect implements JdbcDialect {
    @Override
    public String getCreateTableSql() { /* VARCHAR2 / CLOB types */ }

    @Override
    public String getUpsertSql() { /* MERGE INTO syntax */ }

    // ... override only what differs from ANSI
}
```

Then add a case in `JdbcDialect.from()`:

```java
case "Oracle" -> new OracleDialect();
```

## Database Capability Matrix

| Feature | MySQL | PostgreSQL | H2 | SQLite |
|---------|:-----:|:----------:|:--:|:------:|
| **KV Store** (BaseStore) | ✅ | ✅ | ✅ | ✅ |
| — UPSERT syntax | `ON DUPLICATE KEY` | `ON CONFLICT` | `MERGE INTO` | `ON CONFLICT` |
| — CAS (putIfVersion) | ✅ | ✅ | ✅ | ✅ |
| **State Store** (AgentStateStore) | ✅ | ✅ | ✅ | ✅ |
| — Incremental list append | ✅ | ✅ | ✅ | ✅ |
| — Hash-based change detection | ✅ | ✅ | ✅ | ✅ |
| **Snapshot** (RemoteSnapshotClient) | ✅ LONGBLOB | ✅ BYTEA | ✅ BLOB | ✅ BLOB |
| **Distributed Lock** | ✅ GET_LOCK | ✅ table-based | ✅ table-based | ✅ table-based |
| — Native advisory lock | ✅ | ⚠️ override needed | ❌ | ❌ |
| — Table-based fallback | ✅ | ✅ | ✅ | ✅ |
| **CREATE DATABASE** | ✅ utf8mb4 | N/A (schema) | N/A | N/A |
| **Identifier quoting** | `` `backtick` `` | `"double-quote"` | `"double-quote"` | `"double-quote"` |

### Lock Strategy Notes

- **MySQL**: Uses native `GET_LOCK()` / `RELEASE_LOCK()` via `MysqlLockStrategy`.
  Locks auto-release on connection close (crash-safe).
- **PostgreSQL**: Uses portable `TableBasedLockStrategy` by default. Override
  `lockStrategy()` to use `pg_advisory_lock` for production performance.
- **H2 / SQLite / Unknown**: Uses `TableBasedLockStrategy` — a portable
  INSERT/DELETE-based lock that works on any JDBC database. Note: if the JVM
  crashes, the lock row remains and must be manually removed.

## Testing

### Unit + H2 Integration (default, no Docker)

```bash
mvn -pl agentscope-extensions/agentscope-extensions-jdbc -am test
```

### Testcontainers Real-Database Integration (requires Docker)

```bash
mvn -pl agentscope-extensions/agentscope-extensions-jdbc -am verify -Pintegration
```

## Migration from Deprecated Modules

| Old (deprecated) | New |
|------------------|-----|
| `MysqlDistributedStore.create(ds)` | `JdbcDistributedStore.create(ds)` |
| `PostgresDistributedStore.create(ds)` | `JdbcDistributedStore.create(ds)` |
| `MysqlAgentStateStore(ds)` | `new JdbcAgentStateStore(ds, JdbcDialect.from(ds))` |
| `JdbcStore.builder(ds).dialect(mysqlDialect)` | `JdbcStore.builder(ds).dialect(JdbcDialect.from(ds))` |

The deprecated modules remain fully functional. Migrate at your own pace.
