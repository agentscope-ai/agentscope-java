# PostgreSQL 技能仓库

`agentscope-extensions-skill-postgresql-repository` 把技能存到 PostgreSQL，提供完整的 CRUD：在控制台/业务系统里编辑保存，Agent 这边立即可读。

## 何时使用

- 通过管理后台在线运营技能，希望"改完即生效"。
- 已经有 PostgreSQL 基础设施，不想再引入 Git 依赖。
- 需要把技能存储和业务数据放在同一事务边界。

## 添加依赖

```xml
<dependency>
    <groupId>io.agentscope</groupId>
    <artifactId>agentscope-extensions-skill-postgresql-repository</artifactId>
    <version>${agentscope.version}</version>
</dependency>
```

## 快速上手

```java
import javax.sql.DataSource;
import io.agentscope.core.skill.repository.postgresql.PostgresSkillRepository;

DataSource ds = ...;  // HikariCP、PgBouncer 等连接池

// createIfNotExist=true：自动建 schema 和表；writeable=true：允许写入
PostgresSkillRepository repo = new PostgresSkillRepository(ds, true, true);

Toolkit toolkit = new Toolkit();
repo.getAllSkills().forEach(toolkit::registerSkill);
```

## 使用 Builder

```java
PostgresSkillRepository repo = PostgresSkillRepository.builder(ds)
    .schemaName("my_schema")
    .skillsTableName("my_skills")
    .resourcesTableName("my_resources")
    .createIfNotExist(true)
    .writeable(true)
    .build();
```

## 表结构

`createIfNotExist=true` 时自动创建以下两张表（在指定 schema 下）：

```sql
CREATE TABLE IF NOT EXISTS "agentscope"."agentscope_skills" (
    id BIGSERIAL PRIMARY KEY,
    name VARCHAR(255) NOT NULL UNIQUE,
    description TEXT NOT NULL,
    skill_content TEXT NOT NULL,
    source VARCHAR(255) NOT NULL,
    metadata_json TEXT NULL,
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE IF NOT EXISTS "agentscope"."agentscope_skill_resources" (
    id BIGINT NOT NULL,
    resource_path VARCHAR(500) NOT NULL,
    resource_content TEXT NOT NULL,
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (id, resource_path),
    FOREIGN KEY (id) REFERENCES "agentscope"."agentscope_skills"(id) ON DELETE CASCADE
);
```

- `agentscope_skills`：技能本身，`name` 唯一，`skill_content` 存 `SKILL.md` 全文。
- `agentscope_skill_resources`：技能附带的资源文件（截图、模板等），与 `id` 级联。

与 MySQL 版不同，PostgreSQL 使用 **schema**（而非 database）作为命名空间隔离边界，数据库由 JDBC URL 决定。

## 与已有表兼容

- 旧表如果没有 `metadata_json` 列，仓库会自动降级到"只往返 name + description"的兼容模式，不会主动 `ALTER TABLE`。
- 想升级到完整模式，自行执行 `ALTER TABLE "agentscope"."agentscope_skills" ADD COLUMN metadata_json TEXT NULL;` 即可。

## CRUD 操作

```java
// 写入（save 是 upsert：name 已存在则更新）
AgentSkill skill = ...;
repo.save(List.of(skill), /* overwrite */ true);

// 读取
AgentSkill loaded = repo.getSkill("calculator");
List<String> names = repo.getAllSkillNames();
boolean exists = repo.skillExists("calculator");

// 删除
repo.delete("calculator");
```

写入与删除都在事务里执行，资源表的 `ON DELETE CASCADE` 保证不会出现孤儿资源。

## Builder 配置参数

| 方法 | 说明 |
| --- | --- |
| `schemaName(String)` | 默认 `agentscope` |
| `skillsTableName(String)` | 默认 `agentscope_skills` |
| `resourcesTableName(String)` | 默认 `agentscope_skill_resources` |
| `createIfNotExist(boolean)` | `true` 时自动 `CREATE SCHEMA` + `CREATE TABLE`，默认 `true` |
| `writeable(boolean)` | 是否允许写操作，默认 `true` |
