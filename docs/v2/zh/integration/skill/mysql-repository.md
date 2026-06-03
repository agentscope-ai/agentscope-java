# MySQL 技能仓库

`agentscope-extensions-skill-mysql-repository` 把技能存到 MySQL，提供完整的 CRUD：在控制台/业务系统里编辑保存，Agent 这边立即可读。

## 何时使用

- 通过管理后台在线运营技能，希望"改完即生效"。
- 已经有 MySQL 基础设施，不想再引入 Git 依赖。
- 需要把技能存储和业务数据放在同一事务边界。

## 添加依赖

```xml
<dependency>
    <groupId>io.agentscope</groupId>
    <artifactId>agentscope-extensions-skill-mysql-repository</artifactId>
    <version>${agentscope.version}</version>
</dependency>
```

## 快速上手

```java
import com.zaxxer.hikari.HikariDataSource;
import io.agentscope.core.skill.repository.mysql.MysqlSkillRepository;

HikariDataSource ds = new HikariDataSource();
ds.setJdbcUrl("jdbc:mysql://localhost:3306/agentscope");
ds.setUsername("root");
ds.setPassword("***");

// 第二参数 createIfNotExist=true：自动建库建表
MysqlSkillRepository repo = new MysqlSkillRepository(ds, true);

Toolkit toolkit = new Toolkit();
repo.getAllSkills().forEach(toolkit::registerSkill);
```

## 表结构

`createIfNotExist=true` 时自动创建以下两张表：

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

- `agentscope_skills`：技能本身，`name` 唯一，`skill_content` 存 `SKILL.md` 全文。
- `agentscope_skill_resources`：技能附带的资源文件（截图、模板等），与 `id` 级联。

## 与已有表兼容

- 旧表如果没有 `metadata_json` 列，仓库会自动降级到"只往返 name + description"的兼容模式，不会主动 `ALTER TABLE`。
- 想升级到完整模式，自行执行 `ALTER TABLE agentscope_skills ADD COLUMN metadata_json LONGTEXT NULL;` 即可。

## 自定义库名 / 表名

```java
MysqlSkillRepository repo = new MysqlSkillRepository(
    ds,
    "skill_center",          // 库名
    "ops_skills",            // 技能表
    "ops_skill_resources",   // 资源表
    true                     // 自动建库建表
);
```

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
