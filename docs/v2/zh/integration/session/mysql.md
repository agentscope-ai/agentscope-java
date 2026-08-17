```{note}
本页面内容已迁移至 [分布式存储 — MySQL](../distributed/mysql.md)。以下内容保留作为参考，但建议使用新文档。
```

# MySQL 状态存储

`agentscope-extensions-mysql` 把 AgentScope 的 Agent 状态持久化到 MySQL。适合已有 MySQL 基础设施、需要事务和 SQL 查询能力的场景。

## 添加依赖

```xml
<dependency>
    <groupId>io.agentscope</groupId>
    <artifactId>agentscope-extensions-mysql</artifactId>
    <version>${agentscope.version}</version>
</dependency>
```

数据库驱动按你使用的版本自行引入（例如 `mysql:mysql-connector-j`）。

## 快速上手

```java
import com.zaxxer.hikari.HikariDataSource;
import io.agentscope.core.state.AgentStateStore;
import io.agentscope.extensions.mysql.state.MysqlAgentStateStore;

HikariDataSource ds = new HikariDataSource();
ds.setJdbcUrl("jdbc:mysql://localhost:3306/agentscope?serverTimezone=UTC");
ds.setUsername("root");
ds.setPassword("***");

// 第二个参数 createIfNotExist=true：自动创建库与表
AgentStateStore stateStore = new MysqlAgentStateStore(ds, true);

ReActAgent agent = ReActAgent.builder()
    .name("assistant")
    .model(model)
    .stateStore(stateStore)
    .build();
```

如果库与表已经预先创建好了，可以使用更安全的形式：

```java
AgentStateStore stateStore = new MysqlAgentStateStore(ds);          // 库/表不存在则抛 IllegalStateException
AgentStateStore stateStore = new MysqlAgentStateStore(ds, false);   // 同上，显式声明
```

## 自定义库名 / 表名

```java
AgentStateStore stateStore = new MysqlAgentStateStore(
    ds,
    "agentscope_prod",        // 库名
    "session_state",          // 表名
    true                      // 是否自动创建
);
```

库名、表名只允许 `[a-zA-Z_][a-zA-Z0-9_-]*`，长度 ≤ 64，避免 SQL 注入。

## 表结构

`createIfNotExist=true` 时会自动建表：

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

- `(userId, sessionId)` 二元组会被打包进 `session_id` 列，形如 `{userSegment}:{sessionId}`（`userSegment` 为 `userId`，匿名 session 用 `__anon__`）。
- 单值：`item_index = 0`
- 列表：`item_index = 0,1,2,...`，每项一行；同时另存一行 `state_key='xxx:_hash'` 用作变更检测。

## 直接使用 API

`MysqlAgentStateStore` 实现了 `AgentStateStore` 接口，常用调用如下：

```java
// 单值（匿名 session 时 userId 可传 null）
stateStore.save("user-42", "session-1", "agent_state", state);
Optional<MyState> got = stateStore.get("user-42", "session-1", "agent_state", MyState.class);

// 列表（增量 append；变更时整体重写）
stateStore.save("user-42", "session-1", "messages", listOfMessages);
List<MyState> all = stateStore.getList("user-42", "session-1", "messages", MyState.class);

// 维护
boolean exists = stateStore.exists("user-42", "session-1");
stateStore.delete("user-42", "session-1");
Set<String> sessions = stateStore.listSessionIds("user-42");   // 传 null 列出匿名 session

// 清理（请谨慎，仅用于测试）
stateStore.truncateAllSessions();
```

## 配置参数说明

| 构造参数 / 方法 | 说明 |
| --- | --- |
| `dataSource` | 必填。建议用 HikariCP / Druid 等连接池 |
| `databaseName` | 默认 `agentscope` |
| `tableName` | 默认 `agentscope_sessions` |
| `createIfNotExist` | `true` 时自动 `CREATE DATABASE` + `CREATE TABLE` |

> `truncateAllSessions()` 使用 `TRUNCATE TABLE`，需要 DROP 权限；DDL 不可回滚，仅用于测试或运维清理。
