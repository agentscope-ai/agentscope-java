---
title: MySQL State Store
zh_link: /v2/zh/integration/session/mysql
---

<Note>

This page has been superseded by [Distributed Storage — MySQL](/v2/en/integration/distributed/mysql). Content below is kept for reference.

</Note>


# MySQL State Store

`agentscope-extensions-mysql` persists AgentScope agent state into MySQL. A good fit when you already have a MySQL infrastructure or need transactional / SQL-based access to state data.

## Add the dependency

```xml
<dependency>
    <groupId>io.agentscope</groupId>
    <artifactId>agentscope-extensions-mysql</artifactId>
    <version>${agentscope.version}</version>
</dependency>
```

Bring the matching JDBC driver yourself (e.g. `mysql:mysql-connector-j`).

## Quickstart

```java
import com.zaxxer.hikari.HikariDataSource;
import io.agentscope.core.state.AgentStateStore;
import io.agentscope.extensions.mysql.state.MysqlAgentStateStore;

HikariDataSource ds = new HikariDataSource();
ds.setJdbcUrl("jdbc:mysql://localhost:3306/agentscope?serverTimezone=UTC");
ds.setUsername("root");
ds.setPassword("***");

// Second arg createIfNotExist=true: auto-create database and table
AgentStateStore stateStore = new MysqlAgentStateStore(ds, true);

ReActAgent agent = ReActAgent.builder()
    .name("assistant")
    .model(model)
    .stateStore(stateStore)
    .build();
```

If the database and table are pre-created, use the safer form:

```java
AgentStateStore stateStore = new MysqlAgentStateStore(ds);          // throws IllegalStateException if missing
AgentStateStore stateStore = new MysqlAgentStateStore(ds, false);   // explicit
```

## Custom database / table names

```java
AgentStateStore stateStore = new MysqlAgentStateStore(
    ds,
    "agentscope_prod",        // database name
    "session_state",          // table name
    true                      // auto-create
);
```

Database and table names must match `[a-zA-Z_][a-zA-Z0-9_-]*` and be ≤ 64 chars to avoid SQL injection.

## Schema

When `createIfNotExist=true`, the table is created automatically:

```sql
CREATE TABLE IF NOT EXISTS agentscope_sessions (
    session_id VARCHAR(255) COLLATE utf8mb4_bin NOT NULL,
    state_key  VARCHAR(255) COLLATE utf8mb4_bin NOT NULL,
    item_index INT NOT NULL DEFAULT 0,
    state_data LONGTEXT NOT NULL,
    created_at DATETIME DEFAULT CURRENT_TIMESTAMP,
    updated_at DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (session_id, state_key, item_index)
) DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;
```

The two key columns pin `utf8mb4_bin` while the table default stays `utf8mb4_unicode_ci`: `session_id` and `state_key` are exact identifiers, and the table default is case-insensitive, so without the binary collation two ids differing only in letter case would collide on the primary key and share a row.

### Migrating tables created before this change

`CREATE TABLE IF NOT EXISTS` does not alter a table that already exists, so a deployment created earlier keeps the case-insensitive default and needs an operator to migrate it. `agentscope_store`, `agentscope_sessions` and `agentscope_snapshots` all key on a caller-supplied identifier, so the same reasoning applies to each:

```sql
ALTER TABLE agentscope_store
    MODIFY namespace_path VARCHAR(512) COLLATE utf8mb4_bin NOT NULL,
    MODIFY item_key       VARCHAR(255) COLLATE utf8mb4_bin NOT NULL;

ALTER TABLE agentscope_sessions
    MODIFY session_id VARCHAR(255) COLLATE utf8mb4_bin NOT NULL,
    MODIFY state_key  VARCHAR(255) COLLATE utf8mb4_bin NOT NULL;

ALTER TABLE agentscope_snapshots
    MODIFY snapshot_id VARCHAR(512) COLLATE utf8mb4_bin NOT NULL;
```

Adjust the table names if a prefix or per-table name override was configured. Both paths that create `agentscope_snapshots` — the `agentscope-extensions-jdbc` dialect and the legacy `agentscope-extensions-mysql` snapshot client — now pin the same collation, so the statement above is the only thing a deployment created earlier needs.

Two things to plan for:

- **Read semantics change with the collation.** Comparisons and prefix lookups (`session_id = ?`, `LIKE 'prefix%'`) become case-sensitive, so a lookup that previously matched `SESS-1` for `sess-1` will no longer match. Migrate at a point where no session is mid-flight.
- **Rows may already be gone.** If two keys differing only in case were written before the migration, the second write overwrote the first through `ON DUPLICATE KEY UPDATE`; that lost row cannot be recovered from the table.

- The `(userId, sessionId)` pair is packed into the `session_id` column as `{userSegment}:{sessionId}` (`userSegment` = `userId`, or `__anon__` for anonymous sessions).
- Single value: `item_index = 0`
- List: `item_index = 0, 1, 2, ...` — one row per item; an extra row with `state_key='xxx:_hash'` is used for change detection.

## Direct API usage

`MysqlAgentStateStore` implements the `AgentStateStore` interface:

```java
// Single value (userId may be null for anonymous sessions)
stateStore.save("user-42", "session-1", "agent_state", state);
Optional<MyState> got = stateStore.get("user-42", "session-1", "agent_state", MyState.class);

// List (append-only growth; full rewrite on change)
stateStore.save("user-42", "session-1", "messages", listOfMessages);
List<MyState> all = stateStore.getList("user-42", "session-1", "messages", MyState.class);

// Maintenance
boolean exists = stateStore.exists("user-42", "session-1");
stateStore.delete("user-42", "session-1");
Set<String> sessions = stateStore.listSessionIds("user-42");   // null lists anonymous sessions

// Cleanup (use with care, test/ops only)
stateStore.truncateAllSessions();
```

## Configuration

| Constructor / parameter | Notes |
| --- | --- |
| `dataSource` | Required. Recommended: HikariCP / Druid pool |
| `databaseName` | Default `agentscope` |
| `tableName` | Default `agentscope_sessions` |
| `createIfNotExist` | If `true`, run `CREATE DATABASE` + `CREATE TABLE` automatically |

> `truncateAllSessions()` issues `TRUNCATE TABLE` and requires DROP privilege; DDL is non-rollbackable, so reserve it for tests or ops cleanup.
