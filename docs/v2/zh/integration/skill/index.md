# 技能仓库（Skill Repository）

`AgentSkill` 是 AgentScope 用 Markdown + 资源文件来描述一个可复用"技能"的格式（参考 [Harness · 技能](../../docs/harness/skill)）。`AgentSkillRepository` 接口负责把这些技能从外部存储里加载进来，再交给 `Toolkit` / `ReActAgent` 使用。

`agentscope-extensions-*` 仓库提供了两个开箱即用的实现：

| 扩展 | 后端 | 适合场景 |
| --- | --- | --- |
| [Git Repository](git-repository.md) | 远程 Git 仓库 | 用 Git 流程管控技能版本，跨团队共享 |
| [MySQL Repository](mysql-repository.md) | MySQL 数据库 | 通过控制台 / 业务系统在线编辑、动态发布 |

> Nacos 也提供了一个 `AgentSkillRepository` 实现：见 [Nacos](../infrastructure/nacos)。

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
- **要在管理后台 / 配置中心动态修改、立即生效** → MySQL 或 Nacos
- **多种来源混用** → 实现 `AgentSkillRepository` 自己组合，或多个 repo 都注册到 toolkit
