---
title: JDBC
en_link: /v2/en/integration/distributed/jdbc
---

`agentscope-extensions-jdbc` 提供基于标准 JDBC 的全链路分布式存储实现，是关系型数据库的统一入口：传入任意 JDBC `DataSource`，方言通过 SPI 自动检测，无需针对数据库单独选型。适合已有关系型数据库基础设施的场景。

目前已支持的数据库：

| 数据库     | 定位                      |
| ---------- | ------------------------- |
| MySQL      | 生产环境常用选项          |
| PostgreSQL | 生产环境常用选项          |
| H2         | 内存 / 嵌入式，测试与开发 |
| SQLite     | 嵌入式，轻量单机场景      |

后续将持续扩展对更多关系型数据库的支持，包括 Oracle 以及达梦、高斯、OceanBase 等国产数据库。

> 历史模块 `agentscope-extensions-mysql`、`agentscope-extensions-postgresql`、`agentscope-extensions-skill-mysql-repository`、`agentscope-extensions-skill-postgresql-repository` 已废弃，由本模块统一取代，迁移方式见下文。

## 添加依赖

```xml
<dependency>
    <groupId>io.agentscope</groupId>
    <artifactId>agentscope-extensions-jdbc</artifactId>
    <version>${agentscope.version}</version>
</dependency>
```

数据库驱动按实际使用自行引入（如 `mysql-connector-j`、`postgresql`、`sqlite-jdbc`）。

## 一键配置

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

自定义表名推荐只通过 `tablePrefix` 设置统一前缀；builder 另提供 `storeTableName` / `sessionStateTableName` / `snapshotTableName` 支持逐表完全自定义，通常无需使用：

```java
AbstractJdbcDialect dialect = AbstractJdbcDialect.from(dataSource)
    .tablePrefix("myapp_")            // 默认 agentscope_
    .build();

DistributedStore store = JdbcDistributedStore.create(dataSource, dialect);
```

建表与结构校验统一在 `AbstractJdbcDialect.from(ds).build()` 装配时完成：`autoCreateTable`（默认 true）控制是否执行 DDL，无论开关与否都会校验已启用表组的业务表，缺表或缺列即带参考 DDL 快速失败（Spring 环境在启动时暴露）。表名与前缀仅允许 `[A-Za-z_][A-Za-z0-9_]*`。

**表组开关**：`enableBaseTables`（默认开）与 `enableSkillTables`（默认关）决定 `build()` 建表与校验覆盖哪些表组，与 `autoCreateTable` 正交、彼此也独立——基础表用 MySQL、skill 走 git 通道是合法组合。

```java
AbstractJdbcDialect dialect = AbstractJdbcDialect.from(dataSource)
    .enableBaseTables(true)      // 默认开 —— store、sessions、snapshots
    .enableSkillTables(true)     // 默认关 —— 显式开启才建 skill 表
    .build();                    // 会创建 agentscope_skills / agentscope_skill_resources
```

默认创建的表：`agentscope_store`、`agentscope_sessions`、`agentscope_snapshots`；锁表 `agentscope_distributed_locks` 在首次加锁时创建。开启 skill 表组后额外创建：`agentscope_skills`、`agentscope_skill_resources`。

## 提供的组件

### 1. JdbcAgentStateStore

Agent 状态持久化到数据库表。

```java
import io.agentscope.extensions.jdbc.dialect.AbstractJdbcDialect;
import io.agentscope.extensions.jdbc.state.JdbcAgentStateStore;

AbstractJdbcDialect dialect = AbstractJdbcDialect.from(dataSource).build();  // 建表与校验在此完成

AgentStateStore store = new JdbcAgentStateStore(dataSource, dialect);
```

**表结构**：自动创建的表包含 `session_id`、`state_key`、`item_index`、`state_data`（LONGTEXT JSON）、`version`、`created_at`、`updated_at` 等列，主键为 `(session_id, state_key, item_index)`。`version` 列支撑 `saveIfVersion` 乐观并发控制（CAS 写入）。

### 2. JdbcStore（BaseStore）

工作区文件系统 KV 存储，SQL 全部来自方言层，组件本身与数据库无关。

```java
import io.agentscope.extensions.jdbc.store.JdbcStore;

BaseStore store = JdbcStore.builder(dataSource)
    .dialect(dialect)
    .build();
```

**并发安全**：`putIfVersion` 通过单语句 CAS `UPDATE ... WHERE version = ?` 实现，所有已支持数据库均可用。

### 3. JdbcSnapshotSpec

沙箱快照以 BLOB 存储到数据库表（MySQL 为 LONGBLOB，PostgreSQL 为 BYTEA）。

```java
import io.agentscope.extensions.jdbc.snapshot.JdbcSnapshotSpec;

SandboxSnapshotSpec spec = new JdbcSnapshotSpec(dataSource, dialect);
```

### 4. JdbcSandboxExecutionGuard

分布式锁，锁策略由方言决定：

- **MySQL**：原生 `GET_LOCK()` / `RELEASE_LOCK()`，锁绑定 JDBC 连接，连接关闭时自动释放；lock name 超过 64 字符时自动 hash。
- **PostgreSQL / H2 / SQLite**：基于 `agentscope_distributed_locks` 表的通用锁，不依赖数据库私有语法。

```java
import io.agentscope.extensions.jdbc.sandbox.JdbcSandboxExecutionGuard;

SandboxExecutionGuard guard = JdbcSandboxExecutionGuard.builder(dialect)
    .keyPrefix("myapp:lock:")
    .lockTimeout(Duration.ofMinutes(30))
    .build();
```

> 注意：MySQL named locks 是 server 级别的（非 database 级别）。在共享 MySQL 实例时，使用唯一的 `keyPrefix` 避免冲突。

### 5. JdbcAgentSkillRepository

skill 存储走同一套方言：实现 core 的 `AgentSkillRepository`，本模块支持的每个数据库都能直接作为 skill 通道。

```java
import io.agentscope.core.skill.repository.AgentSkillRepository;
import io.agentscope.extensions.jdbc.skill.JdbcAgentSkillRepository;

AbstractJdbcDialect dialect = AbstractJdbcDialect.from(dataSource)
    .enableSkillTables(true)
    .build();

AgentSkillRepository repo = new JdbcAgentSkillRepository(dataSource, dialect);
```

建两张表：`agentscope_skills` 和 `agentscope_skill_resources`（复合主键 `(id, resource_path)`，外键 `ON DELETE CASCADE`）。与其他组件一样，repository 不碰 schema——表组在 build 时开启；方言没开表组就直接构造，会立刻报错。

两点行为说明：

- `metadata_json` 是必需列。早于该列的旧表会在启动校验时报错并附参考 DDL，按提示 `ALTER TABLE` 补列后重启即可，框架不会代改已有表。
- `delete` 会先显式删掉技能的资源行再删技能行，因此在 SQLite（默认不启用级联外键）上行为也一致；资源路径必须是相对路径且不含 `..`，越出技能目录的路径在保存时即被拒绝，读取回表中的行时也做同样校验。

## 从历史模块迁移

历史模块不建议继续使用，请按自身节奏尽早迁移：

| 已废弃                                                                     | 替代                                                                  |
| -------------------------------------------------------------------------- | --------------------------------------------------------------------- |
| `MysqlDistributedStore.create(ds)` / `PostgresDistributedStore.create(ds)` | `JdbcDistributedStore.create(ds)`                                     |
| `new MysqlAgentStateStore(ds)`                                             | `new JdbcAgentStateStore(ds, AbstractJdbcDialect.from(ds).build())`   |
| `JdbcStore.builder(ds).dialect(mysqlDialect)`                              | `JdbcStore.builder(ds).dialect(AbstractJdbcDialect.from(ds).build())` |
| `MysqlSkillRepository` / `PostgresSkillRepository`（skill-mysql / skill-postgresql 模块） | `new JdbcAgentSkillRepository(ds, AbstractJdbcDialect.from(ds).enableSkillTables(true).build())` |

skill 仓库迁移要点：

- 现行旧模块建的表已包含 `metadata_json`，原样可用；更早的旧表先补上这一列，启动报错里附有参考 DDL。
- "原样可用"有一个例外：旧模块从不拒绝绝对路径或含 `..` 的资源路径，而新实现对读取的行做同样校验——`getSkill` 会拒绝这类行，`getAllSkills` / `getAllSkillNames` 会跳过并告警。名字本身非法的行也无法通过 API 删除，只能 `clearAllSkills` 或直接 SQL 处理。迁移前请先审计 `resource_path` 和技能名；这些值本就无法被下游安全消费。
- 旧模块会隐式创建 `agentscope` 库（MySQL）/ schema（PostgreSQL）；新实现的表放在连接所指向的库里——把 `DataSource` 指向存量表即可。
- `databaseName` / `schemaName` 无对应物——表跟随 DataSource 所指向的库，与基础表一致。表名可用 `skillTableName` / `skillResourcesTableName` 覆盖。相应地，`getSource()` 由 `mysql_<库名>_<表名>` / `postgresql_<schema>_<表名>` 变为 `jdbc_<skillTableName>`：以其为键的消费方（如 skill 暂存缓存命名空间）迁移后会使用新的子目录，旧目录由孤儿 GC 回收。

## 选型建议

| 场景                             | 建议                |
| -------------------------------- | ------------------- |
| 已有关系型数据库，不想引入 Redis | **首选** JDBC 模块  |
| 需要 SQL 审计 / 报表 / 联表查询  | JDBC 模块           |
| 快照数据量大（>100MB）           | BLOB 可行但推荐 OSS |
| 追求最低延迟                     | Redis               |
