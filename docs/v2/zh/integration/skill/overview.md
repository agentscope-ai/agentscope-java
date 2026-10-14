---
title: 概览
en_link: /v2/en/integration/skill/overview
---

`AgentSkill` 是 AgentScope 用 Markdown + 资源文件来描述一个可复用"技能"的格式（参考 [Harness · 技能](/v2/zh/docs/harness/skill)）。`AgentSkillRepository` 接口负责把这些技能从外部存储里加载进来，再交给 `Toolkit` / `ReActAgent` 使用。

`agentscope-extensions-*` 仓库提供了以下开箱即用的实现：

| 扩展 | 后端 | 适合场景 |
| --- | --- | --- |
| [Git Repository](/v2/zh/integration/skill/git-repository) | 远程 Git 仓库 | 用 Git 流程管控技能版本，跨团队共享 |
| [MySQL Repository](/v2/zh/integration/skill/mysql-repository) | MySQL 数据库 | 通过控制台 / 业务系统在线编辑、动态发布 |
| [PostgreSQL Repository](/v2/zh/integration/skill/postgresql-repository) | PostgreSQL 数据库 | 已有 PostgreSQL 基础设施，在线编辑、动态发布 |

> Nacos 也提供了一个 `AgentSkillRepository` 实现：见 [Nacos](/v2/zh/integration/infrastructure/nacos)。

## 接入方式

```java
AgentSkillRepository repo = ...;        // 任选一种实现
List<AgentSkill> skills = repo.getAllSkills();

Toolkit toolkit = new Toolkit();
skills.forEach(toolkit::registerSkill);

ReActAgent agent = ReActAgent.builder()
    .name("Assistant")
    .model(model)
    .toolkit(toolkit)
    .build();
```

## 选型建议

- **想用 Git PR 流程管控、可读可 review** → Git
- **要在管理后台 / 配置中心动态修改、立即生效** → MySQL、PostgreSQL 或 Nacos
- **多种来源混用** → 实现 `AgentSkillRepository` 自己组合，或多个 repo 都注册到 toolkit

## 作用域隔离

技能名是人起的（`code-review`、`git-helper`），不同团队、不同作用域重名是常态。`AgentSkillRepository` 契约要求**每个仓储实例只服务一个作用域**：同名技能跨作用域共存，读写、覆盖（含 `force`）、删除都只发生在实例绑定的作用域内；实例够不着的作用域显式拒绝，而不是静默落到别处。

各来源用后端原生的维度表达作用域，构造时传入即可：

| 来源 | 隔离维度 | 用法 |
| --- | --- | --- |
| FileSystem | `baseDir` 目录 | 每个作用域一个目录、一个仓储实例 |
| Classpath | `resourcePath` 前缀 | 同上 |
| Workspace（Harness） | `RuntimeContext` 用户 | `workspace/<userId>/skills/` 按用户隔离 |
| Git | 仓库 / 分支 / `skillsRoot` | 不同作用域用不同仓库、分支或仓库内子目录 |
| Nacos | 原生 `namespaceId` | 构造时绑定一个命名空间 |
| JDBC | `namespace` 列 | 构造时绑定，见 [JDBC](/v2/zh/integration/distributed/jdbc#作用域隔离namespace) |

一个应用需要多个作用域时，为每个作用域注册一个仓储再组合——`HarnessAgent.builder().skillRepository(a).skillRepository(b)`，后注册的优先级更高——而不是让一个仓储跨作用域查询。

自定义实现遵循同一要求：选你后端原生的维度、构造时绑定、所有操作限定在绑定作用域内、够不着的作用域显式报错。

## 实例复用

多个 Agent 指向同一个技能来源（相同的 baseDir、Nacos namespaceId、Git 仓库等）时，构造一个仓储实例共享给它们，不要为每个 Agent 各建一个。仓储实例按构造参数寻址、可安全共享；重复构造的代价因来源而异——Nacos 仓储每个实例都会建立一条到服务端的客户端连接，Git 仓储在未指定本地路径时各自 clone 一份仓库。Spring 场景下把仓储声明为单例 Bean 注入即可。对应地，不同作用域各建各的实例（见上节）。
