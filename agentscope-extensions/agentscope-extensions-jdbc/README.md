# agentscope-extensions-jdbc

Unified multi-database JDBC dialect abstraction for AgentScope Java. Replaces the
deprecated `agentscope-extensions-mysql` and `agentscope-extensions-postgresql`
modules with a single module that supports MySQL, PostgreSQL, H2, and SQLite
through one aggregated `AbstractJdbcDialect` abstract class.

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

The dialect is auto-detected from the `DataSource` via JDK SPI. No manual dialect
selection required.

## Architecture

Four layers, top-down:

1. **Facade** — `JdbcDistributedStore.create(DataSource)` auto-detects the dialect
   and assembles all four components.
2. **Component layer** (database-agnostic) — `JdbcStore`, `JdbcAgentStateStore`,
   `JdbcRemoteSnapshotClient`, `JdbcSandboxExecutionGuard`. Zero inline SQL — all
   SQL sourced from `BoundSql` returned by dialect methods.
3. **Dialect layer** — `AbstractJdbcDialect` (aggregate abstract class) implements
   all table-domain interfaces (`StoreDialect`, `SessionStateDialect`,
   `SnapshotDialect`). Holds unified table prefix + per-table overrides.
   `AbstractJdbcDialectBuilder` handles SPI detection + table creation.
4. **Vendor layer** — one class per database (`extends AbstractJdbcDialect`),
   overrides only DDL and divergent SQL syntax.

### Adding a New Database

Create a vendor class extending `AbstractJdbcDialect`. Override only the methods
where your database's SQL diverges from ANSI defaults:

```java
public class OracleDialect extends AbstractJdbcDialect {
    @Override
    public String storeCreateTableSql() { /* VARCHAR2 / CLOB types */ }

    @Override
    public BoundSql storeUpsert(...) { /* MERGE INTO syntax */ }

    @Override
    public boolean supports(DatabaseMetaData md) throws SQLException {
        return md.getDatabaseProductName().toLowerCase(Locale.ROOT).contains("oracle");
    }

    // ... override only what differs from ANSI
}
```

Register it in `META-INF/services/io.agentscope.extensions.jdbc.dialect.AbstractJdbcDialect`:

```
io.agentscope.extensions.jdbc.dialect.vendor.OracleDialect
```

No code changes needed elsewhere — SPI auto-discovers the new dialect.

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

### Lock Strategy Notes

The dialect itself is the lock strategy: `AbstractJdbcDialect` implements
`SandboxLockStrategy` and `JdbcSandboxExecutionGuard` delegates every acquisition
to the dialect's `tryEnter(lockName, timeoutSeconds)`.

- **MySQL**: Overrides `tryEnter` to use native `GET_LOCK()` / `RELEASE_LOCK()`.
  Locks auto-release on connection close (crash-safe).
- **PostgreSQL / H2 / SQLite**: Inherit the portable default `tryEnter` — an
  INSERT/DELETE-based lock on the `agentscope_distributed_locks` table that works
  on any JDBC database. A vendor dialect can override `tryEnter` to use native
  advisory locks (e.g. PostgreSQL `pg_advisory_lock`) for production performance.

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
| `MysqlAgentStateStore(ds)` | `new JdbcAgentStateStore(ds, AbstractJdbcDialect.from(ds).build())` |
| `JdbcStore.builder(ds).dialect(mysqlDialect)` | `JdbcStore.builder(ds).dialect(AbstractJdbcDialect.from(ds).build())` |

The deprecated modules remain fully functional. Migrate at your own pace.
