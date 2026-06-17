---
title: "Skill"
description: "Four-layer skill composition, skill marketplaces, the self-learning loop"
---

A skill is a packaged capability: a directory with a `SKILL.md` (purpose + instructions the agent reads), optional reference docs, scripts, samples. Hand it to the agent and it will use it when relevant.

Harness lets you install skills from two places:

- **Skill marketplaces** — Git repo, Nacos, MySQL, classpath, custom stores
- **Workspace** — `workspace/skills/` is shared by everyone; `<userId>/skills/` isolates per user

Both sources are active simultaneously — no need to choose one. On top of that you can enable a **self-learning loop**: the agent drafts skills → review gate → background curator tidies up.

A skill directory looks like:

```
code-reviewer/
├── SKILL.md           # required — YAML frontmatter (name + description) + instructions for the agent
├── references/        # optional — long-form docs the agent reads on demand
│   └── style-guide.md
└── scripts/           # optional — executable scripts the agent can shell out to
    └── run-checks.sh
```

SKILL.md format:

```markdown
---
name: code-reviewer
description: Use when the user asks for code review, style feedback, or PR audits.
---

# Code Reviewer

Steps:
1. Read `references/style-guide.md` for project conventions.
2. Run `scripts/run-checks.sh <target-path>` and summarize the output.
```

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

## Marketplace stores

`skillRepository(...)` is the unified entry point — pass any store.

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

### Multiple stores

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

`workspace/<userId>/skills/` is a **logical path**, not necessarily "a directory on the local disk." Skill files are read and written through the `AbstractFilesystem` abstraction, and where they physically land depends on the [filesystem mode](./filesystem) you configure — so per-user skill isolation is decoupled from the storage backend:

- **Local + shell** — literally `workspace/alice/skills/...` on the host disk.
- **Shared store (remote filesystem)** — the `skills/` prefix is routed to the KV store; per-user isolation shows up as the namespace key `agents/<agentId>/users/alice/skills/...`, consistent across replicas, and edits from an admin console take effect on the next reasoning step.
- **Sandbox (sandbox filesystem)** — the host-side user directory is hydrated into the container's `/workspace` via workspace projection at sandbox start, so the agent reads the same copy inside the sandbox.

Whichever mode you run, `<userId>/skills/` overrides the shared version at the same priority. For the per-mode isolation keys, physical representation, and the role of `userId`, see [Filesystem](./filesystem#how-multi-user-isolation-works).

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

When to use `disableDynamicSkills()`: one-shot tasks; or slow marketplace stores you don't want to refetch per turn. Usually don't touch it.

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

## How the agent reads and runs skills

When the agent reasons, it sees an `<available_skills>` block in the system prompt listing every skill currently in scope:

```xml
<available_skills>
<skill>
  <name>code-reviewer</name>
  <description>Use when the user asks for code review, style feedback, or PR audits.</description>
  <skill-id>code-reviewer_workspace-namespaced</skill-id>
  <files-root>/workspace/skills/code-reviewer</files-root>
</skill>
...
</available_skills>
```

Each entry carries just enough metadata for the agent to decide whether to load it. `<files-root>`, when present, is the absolute path the agent uses for shell execution (see below).

### Reading SKILL.md and resources

To activate a skill the agent calls a built-in tool — `load_skill_through_path`:

- `load_skill_through_path(skillId, path="SKILL.md")` returns the markdown body
- `load_skill_through_path(skillId, path="references/style-guide.md")` returns any other file under the skill directory

How the file gets fetched depends on where the skill came from:

| Skill source | How `path` is resolved |
|--------------|------------------------|
| Project-global dir (Layer 1) | preloaded into memory at registration |
| Marketplace — Git / MySQL / Nacos / classpath (Layer 2) | preloaded into memory by the backend |
| `workspace/skills/` shared (Layer 3) | preloaded into memory at registration |
| `<userId>/skills/` per-user (Layer 4) | SKILL.md preloaded; other files read on demand through `AbstractFilesystem` (per-user namespace + sandbox routing honored automatically) |

The agent doesn't see this difference — `load_skill_through_path` always works the same way. The fallback chain is "in-memory hit → filesystem read → error with an enumeration of every path actually available," so a wrong path returns a useful list rather than a dead end.

### `<files-root>` and shell execution

When a skill ships scripts (e.g. `scripts/run-checks.sh`), the agent needs an absolute path to invoke them via `execute_shell_command`. That path comes from the `<files-root>` element on each skill entry. Resolution depends on the filesystem mode:

| FS mode (shell available?) | Workspace skill `<files-root>` | Marketplace skill `<files-root>` |
|----------------------------|--------------------------------|-----------------------------------|
| Sandbox | `/workspace/skills/<name>` | `/workspace/.skills-cache/<source>/<name>` |
| Local-with-shell | `<wsRoot>/skills/<name>` | `<wsRoot>/.skills-cache/<source>/<name>` |
| Local without shell / Composite | (not rendered — no shell tool registered) | (not rendered) |

So the agent's shell call is always `execute_shell_command("python3 <files-root>/scripts/foo.py")` — no path guessing, no per-source variations to remember.

### Where marketplace files actually live

Marketplace skill resources start as in-memory bytes. For shell execution to work, harness materializes them to `<wsRoot>/.skills-cache/<source>/<name>/` before each reasoning step:

- Per-file SHA-256 dedup — only changed files are rewritten
- Orphan directories (skills no longer published, or repos removed from the builder) are cleaned up in the same pass
- In sandbox mode, `.skills-cache` is in the default workspace projection roots, so the staged tree is hydrated into the sandbox alongside `workspace/skills/` at sandbox start time (and on content change)

Workspace skills (Layer 3 / Layer 4) need no staging — they already live in the workspace tree.

If two repositories report the same `getSource()`, the second is auto-suffixed (`<source>_2`, `<source>_3`, …) with a warning log, so paths and skill-ids never collide.

## Running skills in a sandbox

In [sandbox mode](./filesystem#mode-2-sandbox-sandboxfilesystemspec-family) every file operation and shell command runs inside an isolated container — the host is untouched. That creates a problem: a skill's scripts (`scripts/run-checks.sh`, `scripts/foo.py`, …) are authored on the host, yet the agent has to execute them inside the container. Harness makes this transparent with a three-step "materialize → project → execute-in-container" pipeline, broken down below.

### Which skills end up in the sandbox

Two classes of skills can run in the container, with different staging points:

| Source | Where it lives before the sandbox | Path inside the sandbox |
|--------|-----------------------------------|-------------------------|
| Workspace skills (Layer 3 `workspace/skills/`, Layer 4 `<userId>/skills/`) | already in the workspace tree | `/workspace/skills/<name>` |
| Marketplace skills (Layer 1 project-global, Layer 2 Git / MySQL / Nacos / classpath) | start as in-memory bytes | `/workspace/.skills-cache/<source>/<name>` |

### Step 1: materialize marketplace skills to the host

Marketplace skill resources arrive as in-memory bytes — shell can't execute those directly. Before each reasoning step, `MarketplaceStager` writes them to the host at `<wsRoot>/.skills-cache/<source>/<name>/`:

- **Per-file SHA-256 dedup** — only changed files are rewritten; unchanged ones are skipped.
- **Orphan cleanup** — directories left by skills that are no longer published, or by repos removed from the builder, are deleted in the same pass.
- **Exec-bit recovery** — ingestion turns resources into Strings and discards POSIX mode, so the stager re-derives `+x` heuristically: a shebang (`#!`) at byte 0, or a known script suffix (`.sh`/`.bash`/`.py`/`.rb`/`.pl`/`.js`/`.mjs`), adds the execute bit (following `chmod +x` semantics — only bits that already have read get execute). Pure static assets (`.json`/`.md`/`.txt`) stay 644.

Workspace skills (Layer 3 / Layer 4) skip this step — they already live in the workspace tree.

### Step 2: project the workspace into the sandbox

At sandbox `start()`, harness tars the workspace's "static assets" and hydrates them into the container's `/workspace`. The default projection roots (`workspaceProjectionRoots`) cover exactly the two directories skills need:

```
AGENTS.md  skills/  subagents/  knowledge/  .skills-cache/
```

So `workspace/skills/` (including `<userId>/skills/`) and the `.skills-cache/` produced by step 1 are hydrated together. The projection computes one overall SHA-256 over all included files; if it matches the previous run, hydration is skipped — so repeated `call()`s don't re-transfer identical files, and content only re-enters on change.

Tunables (on `DockerFilesystemSpec` / `KubernetesFilesystemSpec` / other sandbox specs):

| Method | Effect |
|--------|--------|
| `workspaceProjectionRoots(List)` | customize which roots are projected (default includes `skills`, `.skills-cache`) |
| `workspaceProjectionEnabled(false)` | disable projection entirely — with it off there are no skill files in the sandbox, so scripts can't run |

### Step 3: execute scripts inside the container

In sandbox mode, each skill's `<files-root>` in the `<available_skills>` block is rendered with the **in-container** prefix:

| Skill type | `<files-root>` |
|------------|----------------|
| Workspace skill | `/workspace/skills/<name>` |
| Marketplace skill | `/workspace/.skills-cache/<source>/<name>` |

So the agent simply issues:

```
execute_shell_command("python3 /workspace/skills/code-reviewer/scripts/run-checks.sh <target>")
```

That command runs in the container and reads exactly the file that was projected in. The agent doesn't need to know which layer a skill came from — the framework computes the prefix.

> If a sandbox backend mounts the workspace at a non-default location (e.g. AgentRun uses `/home/agentscope/workspace`), the `<files-root>` prefix changes accordingly, and the agent still gets a correct absolute path.

### Persisting script side effects across calls

If a script installs dependencies or generates artifacts (`npm install`, `pip install`, build output) and you want them on the next `call()`, give the sandbox a [snapshot](./filesystem#snapshot-strategies) (`snapshotSpec(...)`). A snapshot captures the whole `/workspace`; the next call on the same scope key restores the snapshot first and then layers projection on top, so installed dependencies don't have to be reinstalled.

### Note: reading SKILL.md doesn't need the sandbox

A common point of confusion: **reading** a skill (`load_skill_through_path` fetching `SKILL.md` / `references/`) goes through memory or the host filesystem and has nothing to do with the sandbox; only **running scripts via shell** requires the files to actually be inside the container. So even with projection disabled, or for a skill that ships no scripts at all, the agent can still read its instructions and reference material normally.

## Tips

**`description` decides whether the agent uses your skill.** The agent only sees name + description initially and decides whether to load details. "Data-analysis tool" is much less useful than "Use when the user asks for stats, reports, or trend charts".

**Keep `SKILL.md` lean.** Aim for ≤ 2k tokens; put reference material under `references/`, scripts under `scripts/`. The agent reads them on demand.

**Use relative paths in SKILL.md and scripts.** Due to the multi-layer isolation of the abstract filesystem, always reference resources and scripts using paths relative to SKILL.md (e.g. `scripts/run.py`, `references/guide.md`). **Do not** hard-code absolute paths like `/workspace/scripts/run.py`. The framework automatically generates the correct `<files-root>` absolute path prefix for each skill based on the active filesystem mode, and the agent uses `<files-root>` to construct full paths at shell-execution time. Hard-coded absolute paths make a skill work only under a specific filesystem mode.

**General capability in marketplaces, project-specific in the workspace.** Code review, table analysis → team Git for shared maintenance. Internal RPC conventions, project naming rules → `workspace/skills/` so they version with the code.

**Per-user dirs are for "override + augment", not primary storage.** Keep critical skills visible to every user.

**Enable self-learning in order**: no point running curator before anyone writes new skills. Start with `enableSkillManageTool`, then add the promotion gate, then the curator.

## Related Pages

- [Workspace](./workspace) — overall layout of `skills/`
- [Filesystem](./filesystem) — multi-tenant isolation and per-user bucketing
- [Architecture](./architecture) — how the skill set is rebuilt each reasoning step
