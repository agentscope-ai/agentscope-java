---
title: Overview
zh_link: /v2/zh/integration/skill/overview
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

## Scope isolation

Skill names are human-chosen (`code-review`, `git-helper`), so the same name from different teams or scopes is normal. The `AgentSkillRepository` contract requires **one scope per repository instance**: the same name coexists across scopes, and reads, overwrites (including `force`), and deletes stay inside the instance's bound scope; a scope the instance cannot address is rejected explicitly, never silently redirected.

Each source expresses the scope with its backend's native dimension, bound at construction:

| Source | Isolation dimension | Usage |
| --- | --- | --- |
| FileSystem | `baseDir` directory | one directory and one repository instance per scope |
| Classpath | `resourcePath` prefix | same |
| Workspace (Harness) | `RuntimeContext` user | `workspace/<userId>/skills/` isolates per user |
| Git | repository / branch / `skillsRoot` | one repository, branch, or in-repo subdirectory per scope |
| Nacos | the native `namespaceId` | one namespace bound at construction |
| JDBC | the `namespace` column | bound at construction, see [JDBC](/v2/en/integration/distributed/jdbc#scope-isolation-namespaces) |

An application needing several scopes registers one repository per scope and composes them — `HarnessAgent.builder().skillRepository(a).skillRepository(b)`, later registrations winning — rather than querying one repository across scopes.

Custom implementations follow the same rules: pick your backend's native dimension, bind it at construction, keep every operation inside the bound scope, and reject unreachable scopes explicitly.

## Instance reuse

When several agents point at the same skill source (the same baseDir, Nacos namespaceId, Git repository, …), construct one repository instance and share it instead of building one per agent. A repository addresses its scope through its constructor arguments and is safe to share; the cost of needless instances varies by source — every Nacos repository instance opens its own client connection to the server, and a Git repository without a local path clones its own copy. In Spring, declare the repository as a singleton bean and inject it. Different scopes, correspondingly, get one instance each (see above).
