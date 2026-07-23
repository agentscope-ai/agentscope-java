# MySQL / JDBC

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
    .maxPersistedContextMessages(200)  // optional: bound session state growth
    .filesystem(new RemoteFilesystemSpec()
            .isolationScope(IsolationScope.USER))
    .build();
```

`agent_state` contains the serialized conversation context, so long-running sessions grow unless a
retention limit is configured. See [Agent State Store](../session/overview.md) for persisted context
limits and application-controlled session deletion.

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
