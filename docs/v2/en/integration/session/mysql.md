# MySQL Session

`agentscope-extensions-session-mysql` persists AgentScope session state into MySQL. A good fit when you already have a MySQL infrastructure or need transactional / SQL-based access to session data.

## Add the dependency

```xml
<dependency>
    <groupId>io.agentscope</groupId>
    <artifactId>agentscope-extensions-session-mysql</artifactId>
    <version>${agentscope.version}</version>
</dependency>
```

Bring the matching JDBC driver yourself (e.g. `mysql:mysql-connector-j`).

## Quickstart

```java
import com.zaxxer.hikari.HikariDataSource;
import io.agentscope.core.session.Session;
import io.agentscope.core.session.mysql.MysqlSession;

HikariDataSource ds = new HikariDataSource();
ds.setJdbcUrl("jdbc:mysql://localhost:3306/agentscope?serverTimezone=UTC");
ds.setUsername("root");
ds.setPassword("***");

// Second arg createIfNotExist=true: auto-create database and table
Session session = new MysqlSession(ds, true);

SessionManager manager = SessionManager.builder().session(session).build();
```

If the database and table are pre-created, use the safer form:

```java
Session session = new MysqlSession(ds);          // throws IllegalStateException if missing
Session session = new MysqlSession(ds, false);   // explicit
```

## Custom database / table names

```java
Session session = new MysqlSession(
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
    session_id VARCHAR(255) NOT NULL,
    state_key  VARCHAR(255) NOT NULL,
    item_index INT NOT NULL DEFAULT 0,
    state_data LONGTEXT NOT NULL,
    created_at DATETIME DEFAULT CURRENT_TIMESTAMP,
    updated_at DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (session_id, state_key, item_index)
) DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;
```

- Single value: `item_index = 0`
- List: `item_index = 0, 1, 2, ...` — one row per item; an extra row with `state_key='xxx:_hash'` is used for change detection.

## Direct API usage

`MysqlSession` implements the `Session` interface:

```java
SessionKey key = SimpleSessionKey.of("user-42");

// Single value
session.save(key, "memory", state);
Optional<MyState> got = session.get(key, "memory", MyState.class);

// List (append-only growth; full rewrite on change)
session.save(key, "messages", listOfMessages);
List<MyState> all = session.getList(key, "messages", MyState.class);

// Maintenance
boolean exists = session.exists(key);
session.delete(key);
Set<SessionKey> all = session.listSessionKeys();

// Cleanup (use with care, test/ops only)
session.truncateAllSessions();
```

## Configuration

| Constructor / parameter | Notes |
| --- | --- |
| `dataSource` | Required. Recommended: HikariCP / Druid pool |
| `databaseName` | Default `agentscope` |
| `tableName` | Default `agentscope_sessions` |
| `createIfNotExist` | If `true`, run `CREATE DATABASE` + `CREATE TABLE` automatically |

> `truncateAllSessions()` issues `TRUNCATE TABLE` and requires DROP privilege; DDL is non-rollbackable, so reserve it for tests or ops cleanup.
