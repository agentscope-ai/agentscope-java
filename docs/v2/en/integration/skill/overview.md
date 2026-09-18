---
title: Overview
---

An `AgentSkill` is AgentScope's Markdown + resource-file format for describing a reusable "skill" (see [Harness · Skill](/v2/en/docs/harness/skill)). The `AgentSkillRepository` interface loads skills from external storage and hands them to the `Toolkit` / `ReActAgent`.

The `agentscope-extensions-*` repository ships the following ready-to-use implementations:

| Extension | Backend | Best for |
| --- | --- | --- |
| [Git Repository](/v2/en/integration/skill/git-repository) | Remote Git repo | Git-based versioning and review |
| [MySQL Repository](/v2/en/integration/skill/mysql-repository) | MySQL database | Online editing via admin console / business systems |
| [PostgreSQL Repository](/v2/en/integration/skill/postgresql-repository) | PostgreSQL database | Existing PostgreSQL infra, online editing |

> Nacos also provides an `AgentSkillRepository` implementation: see [Nacos](/v2/en/integration/infrastructure/nacos).

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
