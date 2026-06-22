# MySQL Skill Repository

`agentscope-extensions-skill-mysql-repository` stores skills in MySQL with full CRUD: edit and save in your admin console / business system, and the Agent picks up changes immediately on the next read.

## When to use

- You operate skills via an admin console and want changes to take effect right away.
- You already have MySQL infrastructure and don't want a Git dependency.
- You want skill storage to share the transactional boundary with your business data.

## Add the dependency

```xml
<dependency>
    <groupId>io.agentscope</groupId>
    <artifactId>agentscope-extensions-skill-mysql-repository</artifactId>
    <version>${agentscope.version}</version>
</dependency>
```

## Quickstart

```java
import com.zaxxer.hikari.HikariDataSource;
import io.agentscope.core.skill.repository.mysql.MysqlSkillRepository;

HikariDataSource ds = new HikariDataSource();
ds.setJdbcUrl("jdbc:mysql://localhost:3306/agentscope");
ds.setUsername("root");
ds.setPassword("***");

// Second arg createIfNotExist=true: auto-create database and tables
MysqlSkillRepository repo = new MysqlSkillRepository(ds, true);

Toolkit toolkit = new Toolkit();
repo.getAllSkills().forEach(toolkit::registerSkill);
```

## Schema

When `createIfNotExist=true`, the following tables are created:

```sql
CREATE TABLE IF NOT EXISTS agentscope_skills (
    id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
    name VARCHAR(255) NOT NULL UNIQUE,
    description TEXT NOT NULL,
    skill_content LONGTEXT NOT NULL,
    source VARCHAR(255) NOT NULL,
    metadata_json LONGTEXT NULL,
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP
) DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS agentscope_skill_resources (
    id BIGINT NOT NULL,
    resource_path VARCHAR(500) NOT NULL,
    resource_content LONGTEXT NOT NULL,
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (id, resource_path),
    FOREIGN KEY (id) REFERENCES agentscope_skills(id) ON DELETE CASCADE
) DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;
```

- `agentscope_skills`: the skills themselves; `name` is unique; `skill_content` stores the full `SKILL.md`.
- `agentscope_skill_resources`: attached resource files (screenshots, templates, ...) cascaded by `id`.

## Compatibility with legacy tables

- If an existing table lacks `metadata_json`, the repository falls back to round-tripping `name` + `description` only. It does not auto-`ALTER TABLE`.
- To upgrade: run `ALTER TABLE agentscope_skills ADD COLUMN metadata_json LONGTEXT NULL;` yourself.

## Custom database / table names

```java
MysqlSkillRepository repo = new MysqlSkillRepository(
    ds,
    "skill_center",          // database
    "ops_skills",            // skill table
    "ops_skill_resources",   // resource table
    true                     // auto-create
);
```

## CRUD

```java
// Write (save is upsert: existing name → update)
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
