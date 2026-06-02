---
title: "Skill"
description: "Four-layer skill composition, skill marketplaces, the self-learning loop"
---

A skill is a packaged capability: a directory with a `SKILL.md` (purpose + instructions the agent reads), optional reference docs, scripts, samples. Hand it to the agent and it will use it when relevant.

Harness lets you install skills from two places:

- **Skill marketplaces** — Git repo, Nacos, MySQL, classpath, custom backends
- **Workspace** — `workspace/skills/` is shared by everyone; `<userId>/skills/` isolates per user

Both sources are active simultaneously — no need to choose one. On top of that you can enable a **self-learning loop**: the agent drafts skills → review gate → background curator tidies up.

> For skill anatomy itself — `SKILL.md` fields, resource loading, tool binding, code execution — see the core skill docs. This page is harness-only.

## A quick example

Plug in your team's skill repo and the agent can use it immediately:

```java
HarnessAgent agent = HarnessAgent.builder()
        .name("assistant")
        .model(model)
        .workspace(workspace)
        .skillRepository(new GitSkillRepository("https://github.com/your-org/team-skills.git"))
        .build();
```

During reasoning, the agent sees skills from the repo and calls `load_skill_through_path` for whichever one it needs.

## Marketplace backends

`skillRepository(...)` is the unified entry point — pass any backend.

### Git

```xml
<dependency>
    <groupId>io.agentscope</groupId>
    <artifactId>agentscope-extensions-skill-git-repository</artifactId>
    <version>${agentscope.version}</version>
</dependency>
```

```java
.skillRepository(new GitSkillRepository("https://github.com/your-org/team-skills.git"))
```

By default, each read does a lightweight remote check, pulling only when HEAD changed. If the repo has a `skills/` subdirectory, that's the root; otherwise the repo root is. To control sync timing yourself: `new GitSkillRepository(url, false)`, then call `repo.sync()` manually.

### Nacos

```xml
<dependency>
    <groupId>io.agentscope</groupId>
    <artifactId>agentscope-extensions-nacos-skill</artifactId>
    <version>${agentscope.version}</version>
</dependency>
```

```java
NacosSkillRepository market = new NacosSkillRepository(aiService, "namespace");
HarnessAgent.builder()
        .skillRepository(market)
        .build();
```

Best for online distribution + change subscription. `market` is `AutoCloseable`; close it on shutdown to release subscriptions.

### MySQL

```java
MysqlSkillRepository registry = MysqlSkillRepository.builder(dataSource)
        .databaseName("agentscope")
        .skillsTableName("skills")
        .createIfNotExist(true)
        .writeable(true)
        .build();

HarnessAgent.builder()
        .skillRepository(registry)
        .build();
```

Common for platform-side skill management. `writeable(true)` lets agents write back; pass `false` for read-only distribution.

### Classpath

Ship skills inside your JAR:

```
src/main/resources/skills/
└── code-reviewer/
    └── SKILL.md
```

```java
.skillRepository(new ClasspathSkillRepository("skills"))
```

Works with both standard JARs and Spring Boot fat JARs.

### Multiple backends

Call `skillRepository(...)` multiple times; later ones win:

```java
HarnessAgent.builder()
        .skillRepository(communityMarket)
        .skillRepository(internalRegistry)
        .skillRepository(teamGitRepo)
        .build();
```

## Workspace skills

Workspace skills need no registration; just put the directory in place.

### Shared by everyone

```
workspace/skills/
└── code-reviewer/
    ├── SKILL.md
    ├── references/
    │   └── style-guide.md
    └── scripts/
        └── run-checks.sh
```

Best for project-specific rules, internal conventions.

### Per-user

To install a skill for a single user, or to override a shared one, place it under a directory named after their `userId`:

```
workspace/
├── skills/code-reviewer/SKILL.md   ← shared version
└── alice/
    └── skills/
        └── code-reviewer/
            └── SKILL.md            ← visible only to Alice; overrides the shared version
```

This requires the caller to pass `userId="alice"` in `RuntimeContext`.

## Conflict resolution

All four sources can yield a same-named skill. Priority from low to high:

| Priority | Source | How to configure |
|----------|--------|------------------|
| 1 (lowest) | Project-global dir | `projectGlobalSkillsDir(Path)`, e.g. `~/.agentscope/skills/` |
| 2 | Marketplaces | `skillRepository(...)`; later registrations win |
| 3 | Workspace shared | `workspace/skills/` |
| 4 (highest) | Per-user | `<userId>/skills/` |

Non-conflicting skills from lower layers still show up; they're only shadowed on name collision.

Example: the team Git has a generic `code-reviewer`; the project's `workspace/skills/code-reviewer/` overrides it for this codebase; Alice's `<alice>/skills/code-reviewer/` overrides that for Alice only — other users still see the project version.

## Common builder options

| Method | Notes |
|--------|-------|
| `skillRepository(repo)` | Append a marketplace; callable multiple times |
| `skillRepositories(list)` | Replace all marketplaces at once |
| `projectGlobalSkillsDir(path)` | Enable the project-global dir; skipped if missing |
| `disableDynamicSkills()` | Turn off "re-merge before each reasoning"; merge once at build |

Subagents inherit the parent's marketplaces and project-global dir automatically.

When to use `disableDynamicSkills()`: one-shot tasks; or slow marketplace backends you don't want to refetch per turn. Usually don't touch it.

## Self-learning loop (optional)

Harness stitches together a loop that lets the agent draft / curate / archive skills on its own. Each stage is independently opt-in:

### Step 1: let the agent write skills

```java
HarnessAgent.builder()
    ...
    .enableSkillManageTool(SkillManageConfig.defaults())
    .build();
```

Once enabled, the agent gets two tools:

- `propose_skill` — write a new skill as a draft to `skills/_drafts/<name>/`, pending review
- `skill_manage` — edit existing skills (create / edit / add ancillary files / delete)

Skip the "draft → review" two-step and let the agent's writes go live directly: `.enableSkillManageTool(true)` (`autoPromote=true`). Not recommended for production.

The framework also auto-bumps a usage counter every time the agent calls `load_skill_through_path` / `read_skill`, kept in `skills/.usage.json` — data that powers cleanup and canary rollout below.

### Step 2: add a review gate + visibility filter

```java
.enableSkillPromotionGate(
    new LocalApprovalGate(LocalApprovalGate.defaultPrompter()),    // who reviews
    new CompositeFilter(List.of(                                    // how to expose
        new EnvironmentFilter("prod", skillUsageStore),
        new CanaryFilter(0.10, skillUsageStore)
    )))
.environment("prod")
```

- **Gate** — drafts must pass it before being promoted to real skills. Three built-in flavors: reject-all (default), local human approval (stdin etc.), notify-and-wait.
- **Visibility filter** — decides which agent-authored skills the agent can see during reasoning. Compose by deployment environment tag, canary percentage, allow-list.

### Step 3: background periodic curation

```java
.enableSkillCurator(SkillCuratorConfig.builder()
    .intervalHours(7 * 24)        // weekly
    .minIdleHours(2)              // only when call-gap ≥ 2h
    .staleAfterDays(30)
    .archiveAfterDays(90)
    .build())
```

A throttled background job runs: skills unused for 30+ days become stale; for 90+ days move into `skills/.archive/`. An optional LLM "umbrella merge" pass can also run (dry-run by default — emits reports, doesn't actually change files).

### Programmatic triggers

From application code:

```java
List<SkillAuditLog.Entry> entries = agent.queryAudit(LocalDate.now(), e -> true);

agent.runCuratorOnce()                                       // run a curation now (bypasses throttle)
     .subscribe(report -> System.out.println(report));

agent.promoteSkill("notes-taker", "alice")                   // manually promote a draft
     .subscribe(result -> System.out.println(result));
```

## Tips

**`description` decides whether the agent uses your skill.** The agent only sees name + description initially and decides whether to load details. "Data-analysis tool" is much less useful than "Use when the user asks for stats, reports, or trend charts".

**Keep `SKILL.md` lean.** Aim for ≤ 2k tokens; put reference material under `references/`, scripts under `scripts/`. The agent reads them on demand.

**General capability in marketplaces, project-specific in the workspace.** Code review, table analysis → team Git for shared maintenance. Internal RPC conventions, project naming rules → `workspace/skills/` so they version with the code.

**Per-user dirs are for "override + augment", not primary storage.** Keep critical skills visible to every user.

**Enable self-learning in order**: no point running curator before anyone writes new skills. Start with `enableSkillManageTool`, then add the promotion gate, then the curator.

## Related Pages

- [Workspace](./workspace) — overall layout of `skills/`
- [Filesystem](./filesystem) — multi-tenant isolation and per-user bucketing
- [Architecture](./architecture) — how the skill set is rebuilt each reasoning step
