# PostgreSQL Skill Repository

`agentscope-extensions-skill-postgresql-repository` stores skills in PostgreSQL with full CRUD: edit and save in your admin console / business system, and the Agent picks up changes immediately on the next read.

## When to use

- You operate skills via an admin console and want changes to take effect right away.
- You already have PostgreSQL infrastructure and don't want a Git dependency.
- You want skill storage to share the transactional boundary with your business data.

## Add the dependency

```xml
<dependency>
    <groupId>io.agentscope</groupId>
    <artifactId>agentscope-extensions-skill-postgresql-repository</artifactId>
    <version>${agentscope.version}</version>
</dependency>
```

## Quickstart

```java
import javax.sql.DataSource;
import io.agentscope.core.skill.repository.postgresql.PostgresSkillRepository;

DataSource ds = ...;  // HikariCP, PgBouncer, etc.

// createIfNotExist=true: auto-create schema and tables; writeable=true: allow writes
PostgresSkillRepository repo = new PostgresSkillRepository(ds, true, true);

Toolkit toolkit = new Toolkit();
repo.getAllSkills().forEach(toolkit::registerSkill);
```

## Using the Builder

```java
PostgresSkillRepository repo = PostgresSkillRepository.builder(ds)
    .schemaName("my_schema")
    .skillsTableName("my_skills")
    .resourcesTableName("my_resources")
    .createIfNotExist(true)
    .writeable(true)
    .build();
```

## Schema

When `createIfNotExist=true`, the following tables are created (under the configured schema):

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

- `agentscope_skills`: the skills themselves; `name` is unique; `skill_content` stores the full `SKILL.md`.
- `agentscope_skill_resources`: attached resource files (screenshots, templates, ...) cascaded by `id`.

Unlike the MySQL variant, PostgreSQL uses **schemas** (not databases) as the namespace isolation boundary — the database is selected via the JDBC URL.

## Compatibility with legacy tables

- If an existing table lacks `metadata_json`, the repository falls back to round-tripping `name` + `description` only. It does not auto-`ALTER TABLE`.
- To upgrade: run `ALTER TABLE "agentscope"."agentscope_skills" ADD COLUMN metadata_json TEXT NULL;` yourself.

## CRUD

```java
// Write (save is upsert: existing name -> update)
AgentSkill skill = ...;
repo.save(List.of(skill), /* overwrite */ true);

// Read
AgentSkill loaded = repo.getSkill("calculator");
List<String> names = repo.getAllSkillNames();
boolean exists = repo.skillExists("calculator");

// Delete
repo.delete("calculator");
```

Writes and deletes run in transactions; the resource table's `ON DELETE CASCADE` ensures no orphaned resources.

## Builder reference

| Method | Notes |
| --- | --- |
| `schemaName(String)` | Default `agentscope` |
| `skillsTableName(String)` | Default `agentscope_skills` |
| `resourcesTableName(String)` | Default `agentscope_skill_resources` |
| `createIfNotExist(boolean)` | `true` to auto-`CREATE SCHEMA` + `CREATE TABLE`, default `true` |
| `writeable(boolean)` | Whether write operations are allowed, default `true` |
