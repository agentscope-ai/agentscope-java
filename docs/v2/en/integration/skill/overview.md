# Skill Repository

An `AgentSkill` is AgentScope's Markdown + resource-file format for describing a reusable "skill" (see [Harness · Skill](../../docs/harness/skill)). The `AgentSkillRepository` interface loads skills from external storage and hands them to the `Toolkit` / `ReActAgent`.

The `agentscope-extensions-*` repository ships the following ready-to-use implementations:

| Extension | Backend | Best for |
| --- | --- | --- |
| [Git Repository](git-repository.md) | Remote Git repo | Git-based versioning and review |
| [MySQL Repository](mysql-repository.md) | MySQL database | Online editing via admin console / business systems |
| [PostgreSQL Repository](postgresql-repository.md) | PostgreSQL database | Existing PostgreSQL infra, online editing |

> Nacos also provides an `AgentSkillRepository` implementation: see [Nacos](../infrastructure/nacos).

## Wiring

```java
AgentSkillRepository repo = ...;        // any implementation
List<AgentSkill> skills = repo.getAllSkills();

Toolkit toolkit = new Toolkit();
skills.forEach(toolkit::registerSkill);

ReActAgent agent = ReActAgent.builder()
    .name("Assistant")
    .model(model)
    .toolkit(toolkit)
    .build();
```

## Choosing one

- **Want Git PR flow, reviewable text** → Git
- **Want admin console / live config edits** → MySQL, PostgreSQL, or Nacos
- **Mix multiple sources** → implement `AgentSkillRepository`, or register multiple repos to the same toolkit
