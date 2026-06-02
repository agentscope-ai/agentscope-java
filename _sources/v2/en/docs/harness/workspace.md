---
title: "Workspace"
description: "HarnessAgent's foundation: directory layout, loading model, behavior across filesystem modes, isolation & overrides, deep dive on key contents"
---

## Design philosophy

The workspace is `HarnessAgent`'s foundation. **Everything that needs to survive a call or restart is expressed as a directory of plain Markdown / JSON files**, not scattered in code, not pinned to a particular database table.

Three guiding ideas:

**1. Directories and files are the source of truth.** Persona (`AGENTS.md`), long-term memory (`MEMORY.md` + `memory/`), domain knowledge (`knowledge/`), subagent declarations (`subagents/`), skills (`skills/`), plan files (`plans/`), tool allowlist + MCP (`tools.json`) — all files. You can `cat` them, `git diff` them, send them to a human for review, have another process consume them.

**2. Content splits into three lifecycles, kept distinct.**

| Kind | Written by | Read by | Examples |
|------|------------|---------|----------|
| **Static assets** (engineer-edited) | You / your team | Framework injects into the system prompt each turn, or reads on demand at call time | `AGENTS.md`, `knowledge/`, `skills/`, `subagents/`, `tools.json` |
| **Runtime state** (rewritten on every call) | Framework / agent | Framework restores it on the next call | `agents/<agentId>/context/`, `agents/<agentId>/sessions/`, `agents/<agentId>/tasks/`, `plans/` |
| **Long-term memory** (accumulated across sessions) | Agent + background tasks | Framework injects into the system prompt + agent queries via tools | `MEMORY.md`, `memory/YYYY-MM-DD.md` |

They live in one tree purely for deployment convenience (copy a directory, get a complete agent). Inside the framework they travel different read/write paths.

**3. Workspace decouples from filesystem.** The same directory layout lands in one of three places: local disk, shared KV store (Redis / JDBC), or sandbox container. This decoupling is what lets you switch deployment shape without touching agent code. See [Filesystem](./filesystem) for the three modes.

## Workspace directory layout

```
.agentscope/workspace/
├── AGENTS.md                    ← static: persona + behavior rules
├── MEMORY.md                    ← long-term: curated long-term facts
├── tools.json                   ← static: MCP servers + tool allow/deny (optional)
├── memory/                      ← long-term: append-only daily fact log
│   └── YYYY-MM-DD.md
├── knowledge/                   ← static: knowledge entry + reference files
│   ├── KNOWLEDGE.md
│   └── ...
├── skills/                      ← static: one subdir per skill, each with a SKILL.md
│   └── <skill-name>/SKILL.md
├── subagents/                   ← static: subagent specs (filename = agent_id)
│   └── <agent-id>.md
├── plans/                       ← runtime: plan files written in Plan Mode
│   └── PLAN.md
└── agents/<agentId>/            ← runtime: each agent's runtime root
    ├── context/<sessionId>/     ← runtime: session snapshots (serialized AgentState)
    ├── sessions/                ← runtime: session index + never-compacted log
    │   ├── sessions.json
    │   └── <sessionId>.log.jsonl
    └── tasks/                   ← runtime: subagent background task records
        └── <sessionId>.json
```

**Only `AGENTS.md` is something you actually need to write** (skip it and the agent still runs — you just lose the persona injection). Everything else appears as you turn on the matching capability:

- Enable memory compaction (`.compaction(...)`) → `memory/` + `MEMORY.md`
- Drop in subagent specs → `subagents/`
- Install skills → `skills/`
- Enable Plan Mode → `plans/`
- Any `call()` run → `agents/<agentId>/`

## Builder configuration

```java
HarnessAgent agent = HarnessAgent.builder()
    .name("MyAgent")
    .model(model)
    .workspace(Paths.get(".agentscope/workspace"))   // omit → ${user.dir}/.agentscope/workspace
    .additionalContextFile("SOUL.md")                // any workspace-relative path, inlined in full
    .additionalContextFile("PREFERENCES.md")
    .maxContextTokens(8000)                          // MEMORY injection budget
    .build();
```

Minimum `AGENTS.md` skeleton:

```markdown
# MyAgent

You are an XX assistant. Follow these behavior guidelines.

## Behavior
- ...
- ...
```

Opt-out switches (rare in production, useful for debugging or self-management):

| Method | What it disables |
|--------|------------------|
| `disableWorkspaceContext()` | system-prompt injection (`AGENTS.md` / `MEMORY.md` / `knowledge/`) |
| `disableMemoryHooks()` | memory flush + background maintenance |
| `disableMemoryTools()` | `memory_search` / `memory_get` / `session_search` tools |
| `disableSubagents()` | the entire subagent subsystem |
| `disableDynamicSkills()` | per-turn skill re-merge; falls back to one-shot merge at build time |
| `disableToolsConfig()` | reading `tools.json` |
| `disableSessionPersistence()` | session auto-persistence |

## How workspace content gets loaded

### System-prompt assembly per turn

Before every reasoning step, `WorkspaceContextMiddleware` (`io.agentscope.harness.agent.middleware`) assembles the following sections and **appends them to** the `sysPrompt` you set on the builder to form the final system message:

| Section | Source | Budgeted |
|---------|--------|----------|
| `## Session Context` | Template (today's date, OS, workspace absolute path, temp dir, current `sessionId`) | no |
| `## Domain Knowledge` / `## Memory Recall` / `## Memory Persistence` guidance | Built-in templates (teach the model how to use memory + navigate knowledge) | no |
| `## Workspace` section | Template, **branches per filesystem mode** (see below) — tells the model whether it runs locally / sandboxed / on a remote store | no |
| `## Workspace Files (Injected)` notice | Framework auto-loads the following files from the workspace into a `<loaded_context>` XML block | see below |
| `<agents_context>` | Full `AGENTS.md` | unlimited |
| `<memory_context>` | `MEMORY.md`, char-truncated when over the remaining budget with a "use memory_search for older entries" note | `maxContextTokens`, default 8000 |
| `<domain_knowledge_context>` | Full `knowledge/KNOWLEDGE.md` + listing of every file under `knowledge/` | unlimited (filenames only as the catalog) |
| `<x_md>` / `<y_md>` | Anything you added with `additionalContextFile("X.md")` | unlimited |

Key points:

- **Re-assembled every turn.** Edit `AGENTS.md` or `MEMORY.md` and the next `call()` picks up the change — no restart, no rebuild.
- **`MEMORY.md` is token-estimated before injection.** Overflow truncates by character count with a trailing note that nudges the model toward `memory_search`.
- **`knowledge/` is a directory index + entry file.** The full tree never enters the prompt — only `KNOWLEDGE.md` plus a listing of paths; the agent reads what it needs with `read_file`.

### Two-layer reads (filesystem-first + local fallback)

For every "file injected into the prompt" (`AGENTS.md` / `MEMORY.md` / `knowledge/KNOWLEDGE.md` / `additionalContextFile`), `WorkspaceManager.readWithOverride()` does a **two-layer read**:

```
1. Ask the configured AbstractFilesystem: do you have this relative path?
   ├─ yes → return that content (the "override" layer)
   └─ no  → fall through to step 2
2. Read local disk at workspace.resolve(relativePath)
```

Writes always go through layer 1 (the filesystem backend), never directly to local disk.

This pattern earns its keep in **shared-store mode**: the first replica starts with the team-git-synced `AGENTS.md` template available on local disk, so it works immediately; later any override (e.g. from an admin console editor) lands in the shared KV, and every replica's next `call()` reads the latest version. Template is fallback, remote override is truth.

### Override precedence with multiple users sharing one workspace

`RuntimeContext.userId` is the multi-user key — it lets one agent instance serve many users without crosstalk.

For **runtime data** (sessions / tasks / memory), the framework prefixes paths via the configured `NamespaceFactory` (local-mode → path prefix, remote-mode → KV namespace, sandbox-mode → state slot). Details in the next section, "How session and memory are stored".

For **static assets** (notably `skills/` and `subagents/`), a per-user directory **overrides** the workspace-shared version:

```
workspace/
├── skills/code-reviewer/SKILL.md     ← shared (visible to everyone)
├── subagents/researcher.md           ← shared
└── alice/
    ├── skills/
    │   └── code-reviewer/
    │       └── SKILL.md              ← only visible to alice; overrides shared
    └── subagents/
        └── researcher.md             ← only visible to alice
```

When called with `RuntimeContext.userId="alice"`, the framework looks in `alice/skills/code-reviewer/` first and falls back to `skills/code-reviewer/`. Skills unique to a lower layer remain visible; only same-name conflicts are shadowed by the higher layer. Full precedence table in [Skills — Same-name conflicts](./skill#same-name-conflicts).

### Loading behavior under each filesystem mode

The workspace is a logical layout; physical placement is up to [Filesystem](./filesystem). The same directory loads differently depending on mode — illustrated below.

**Mode 1 · Shared store (`RemoteFilesystemSpec`) — template + remote override**

```java
HarnessAgent agent = HarnessAgent.builder()
    .name("store")
    .model(model)
    .workspace(workspace)
    .filesystem(new RemoteFilesystemSpec(redisStore)
        .isolationScope(IsolationScope.USER))      // namespace per userId
    .build();
```

- **How it loads**: at each turn, `AGENTS.md` / `MEMORY.md` / `tools.json` are served by an overlay with the remote KV as the upper layer and the workspace template as the read-only lower layer. The local `<workspace>/AGENTS.md` is a **read-only seed** — used at first boot or to sync across replicas; if the remote KV has a per-user copy under the same key, the remote wins.
- **Routing**: `memory/` / `skills/` / `subagents/` / `knowledge/` / `agents/<id>/sessions/` / `agents/<id>/tasks/` are namespaced per `IsolationScope` (default USER → one namespace per `userId`; see [Filesystem — IsolationScope](./filesystem#isolationscope--bucketing-across-users-and-replicas)).
- **Best practice**: git-sync the team-agreed `AGENTS.md` / `knowledge/` / shared `skills/` to every replica's local disk as the template; let runtime outputs (`MEMORY.md`, `memory/`, `agents/<id>/...`) accrete in the KV.

**Mode 2 · Sandbox (`DockerFilesystemSpec` / K8s / E2B / AgentRun) — projection + hydrate**

```java
HarnessAgent agent = HarnessAgent.builder()
    .name("sandbox")
    .model(model)
    .workspace(workspace)
    .filesystem(new DockerFilesystemSpec()
        .image("ubuntu:24.04")
        .isolationScope(IsolationScope.SESSION))
    .build();
```

- **How it loads**: when the sandbox starts, the framework tars the workspace's "static assets" (`AGENTS.md`, `skills/`, `subagents/`, `knowledge/`, plus other projection roots) and hydrates them into `/workspace` inside the container. `AGENTS.md` etc. still follow the two-layer read (sandbox first, host template fallback).
- **Dedup & incremental**: projections are compared by content hash; unchanged → skip; changed files are rewritten incrementally with SHA-256.
- **Runtime data**: `MEMORY.md`, `memory/`, `agents/<id>/...` all live inside the sandbox; sandbox snapshots preserve them — the next `call()` with the same `sessionId` restores `node_modules`, `pip install` results, and everything else.
- **Best practice**: keep code execution / shell out of the host. The host only carries the workspace "seed" (team-git-synced persona + shared skills + knowledge). This is the default mode for running untrusted code in production.

**Mode 3 · Local + shell (default `LocalFilesystemSpec` or no `filesystem(...)`) — direct read / write**

```java
HarnessAgent agent = HarnessAgent.builder()
    .name("local")
    .model(model)
    .workspace(workspace)
    // omit .filesystem(...) = local + shell
    .build();
```

- **How it loads**: all files are read directly from `<workspace>/`; no overlay. Per-user overrides like `<userId>/skills/` are simple directory-prefix switching.
- **Path safety**: default `ROOTED` mode — absolute paths are only allowed under the `workspace` and `project` (shell `cwd`) roots; `..` traversal is rejected by the path policy.
- **Best practice**: single process / local dev / unit tests / trusted env. Do **not** run untrusted code here in production — `execute` is host `sh -c`.

## How session and memory are stored

Both of these are "runtime / long-term" content — you don't hand-edit them. Their physical location is tied to the filesystem mode.

### Session (runtime snapshot)

When a `call()` completes, `AgentState` (chat history, compaction summary, permission rules, Plan Mode state, tool state) is serialized to JSON and written to:

```
<workspace>/[<namespace>/]agents/<agentId>/context/<sessionId>/agent_state.json
```

The next `call()` with the same `sessionId` loads it back. This is `WorkspaceSession` (`io.agentscope.harness.agent.session.WorkspaceSession`) — the default `Session` implementation when `HarnessAgent` is built without `.session(...)`, backed by `JsonSession`.

`agents/<agentId>/sessions/` also holds:

- **`sessions.json`** — the agent's session index (key = sessionId, value = summary + updatedAt).
- **`<sessionId>.log.jsonl`** — the **never-compacted** raw conversation log, append-only. `session_search` / `session_history` query it.

> `WorkspaceSession` is single-machine only. Multi-replica production must switch to a distributed backend (`RedisSession` / `MysqlSession` / …). If you have configured `filesystem(SandboxFilesystemSpec)` or `filesystem(RemoteFilesystemSpec)` without swapping the Session, `build()` raises `IllegalStateException` — a forced reminder not to make runtime state a single point of failure.

Full details (recovery flow, cross-node continuation, `SessionKey`) live in [Context](./context).

### Memory (long-term)

Two layers:

```
workspace/
├── MEMORY.md                  ← curated long-term memory, injected each turn
└── memory/
    └── YYYY-MM-DD.md          ← append-only daily fact log (no dedup)
```

Write path:

- Before compaction, `MemoryFlushMiddleware` extracts new facts from the prefix of the conversation into `memory/YYYY-MM-DD.md` (append).
- A throttled background task periodically merges/dedups `memory/` and rewrites `MEMORY.md`.
- `MEMORY.md` is injected (budgeted) into the system prompt every turn.

Read path:

- Framework reads `MEMORY.md` itself (two-layer; filesystem first).
- Agent can actively call `memory_search` / `memory_get` for older entries. See [Memory](./memory).

### How namespace isolation maps to physical location

`WorkspaceManager.resolveRuntimeDataPath()` asks the `NamespaceFactory` what namespace the current `RuntimeContext` maps to. The namespace then materializes per filesystem mode:

| Mode | Physical location of runtime data | Multi-user isolation mechanism |
|------|----------------------------------|-------------------------------|
| Local + shell | `<workspace>/<userId>/agents/<agentId>/...` | path prefix |
| Shared store (KV) | KV key prefix, e.g. `namespace=alice/memory/...` | KV namespace |
| Sandbox | sandbox state slot key (with `IsolationScope.USER`) | sandbox instance isolation |

Without `userId`, single-tenant default applies and everyone shares one root.

> **Static assets** vs **runtime data**: `AGENTS.md`, `tools.json`, `knowledge/` and friends are **not** auto-partitioned per userId — they are shared across users, and the only way to differentiate is to add per-user override directories (`<userId>/skills/...`, `<userId>/subagents/...`). What follows `userId` is runtime data (sessions, tasks, memory).

## Deep dive on key directories

### `skills/`

A skill is a packaged capability — a directory containing `SKILL.md` (description + instructions for the agent), optionally with reference docs and scripts.

```
skills/code-reviewer/
├── SKILL.md               ← YAML frontmatter (name + description) + instructions
├── references/style-guide.md   ← optional, agent reads on demand
└── scripts/run-checks.sh       ← optional, agent invokes via execute_shell_command
```

There are four registration layers (low → high priority):

1. `projectGlobalSkillsDir(Path)` — project global, e.g. `~/.agentscope/skills/`
2. `skillRepository(...)` — marketplace backends (Git / Nacos / MySQL / classpath)
3. `workspace/skills/` — workspace shared
4. `<userId>/skills/` — per-user (overrides all above)

Unique skills at a lower layer remain visible; same-name skills are shadowed by the higher layer. Each turn, `DynamicSkillMiddleware` re-merges and renders an `<available_skills>` block (name + description only) into the system prompt. The agent calls `load_skill_through_path` to pull full details when relevant. Full mechanics in [Skills](./skill).

### `subagents/`

Each `<agent-id>.md` is a subagent declaration (filename = `agent_id`). YAML frontmatter describes identity, model, tool allowlist, workspace strategy; body is the subagent's system prompt.

```markdown
---
description: Code review specialist. Use when the user needs a PR review, style feedback, or static checks.
workspace:
  mode: isolated         # isolated (default) | shared
model: qwen3-max         # optional; defaults to inheriting the parent
tools: [read_file, grep_files]   # optional; inherited-tool allowlist
---

You are a code review subagent…
```

Loading: `AgentSpecLoader` **non-recursively** scans `workspace/subagents/*.md` at build time and merges with any declarations you registered programmatically via `.subagent(SubagentDeclaration...)`. The main agent invokes them via `agent_spawn agent_id="reviewer" task="..."`.
Full details (sync vs background, remote subagents, stream forwarding, task storage) in [Subagent](./subagent).

### `tools.json`

A JSON file at the workspace root, read once during `build()`:

```jsonc
{
  // allowlist: when non-empty, only listed tools survive
  "allow": ["read_file", "grep_files", "execute"],
  // denylist: listed tools are always removed (wins over allow)
  "deny":  ["write_file"],
  // MCP servers, keyed by name
  "mcpServers": {
    "amap": {
      "transport": "streamableHttp",
      "url": "https://mcp.amap.com/mcp?key=${AMAP_API_KEY}"
    },
    "local-py": {
      "transport": "stdio",
      "command": "python",
      "args": ["mcp_servers/my_server.py"],
      "env": {"PYTHONUNBUFFERED": "1"}
    }
  }
}
```

Behavior notes:

- **MCP servers are registered into the toolkit once at build time**; the agent sees the tools they expose.
- **`allow` / `deny` are applied after every tool has been registered** — including Harness built-ins (`read_file` / `memory_search` / `agent_spawn` / …). **When you use `allow` to whitelist, list the built-ins you want to keep too**, otherwise they get filtered out alongside everything else.
- `${ENV_VAR}` syntax substitutes environment variables; missing variables warn and substitute the empty string.
- Don't want a file? Pass `builder.toolsConfig(ToolsConfig.builder()...)` directly, or fully disable reading with `disableToolsConfig()`.
- Under shared-store mode, `tools.json` follows the same "remote upper, local-template lower" overlay described above.

### `plans/`

Plan files written in Plan Mode land here. Default `plans/PLAN.md`, changeable via `.planFileDirectory("design-docs")`.

```
plans/
└── PLAN.md           ← current plan written by plan_write
```

Note: `PlanModeContext` (whether the plan phase is active, current plan file path) lives in `AgentState` — it is **runtime state**, persisted under `agents/<agentId>/context/<sessionId>/`. The files under `plans/` are only the markdown content itself. See [Plan Mode](./plan-mode).

### `agents/<agentId>/`

This is the **runtime root**, framework-written and rarely hand-edited:

```
agents/<agentId>/
├── context/<sessionId>/
│   └── agent_state.json      ← serialized AgentState snapshot (written at the end of each call)
├── sessions/
│   ├── sessions.json          ← session index for this agent
│   └── <sessionId>.log.jsonl  ← never-compacted raw conversation log (append-only)
└── tasks/
    └── <sessionId>.json       ← subagent background task records (taskId → TaskRecord)
```

For cross-node recovery / multi-replica deployments this data must be shared (either `RedisSession` + `RemoteFilesystemSpec`, or sandbox with distributed state). See [Context](./context) and [Filesystem](./filesystem).

### `knowledge/`

```
knowledge/
├── KNOWLEDGE.md         ← entry / overview, injected in full into the system prompt
├── api-reference.md
├── domain-terms.md
└── ...
```

At load time:

- The full `KNOWLEDGE.md` goes into `<domain_knowledge_context>`.
- Other files under the same tree (any depth) only contribute their **path listing** to the prompt; the agent reads them on demand with `read_file` / `grep_files` / `glob_files`.

This "details on disk, index in the prompt" pattern keeps token budget bounded even with a large knowledge base.

## Safety rules for writing to the workspace

`additionalContextFile`, `writeUtf8WorkspaceRelative`, `memory_get`, and friends accept **workspace-relative paths**. The framework does basic path-traversal validation (refusing `../../etc/passwd` and similar escapes).

When you need to write files, **go through `HarnessAgent#getWorkspaceManager()`, not `java.nio.Files`** — the latter writes to the wrong place under sandbox or shared-store modes (it lands on the host disk rather than inside the sandbox / in the KV). Exception: builder-time bootstrap scripts (e.g. an `initWorkspaceIfAbsent` that seeds `AGENTS.md`) — there is no runtime context yet, and `java.nio.Files` is correct because the intent is to write the local template.

## Related Pages

- [Architecture](./architecture) — how the system prompt is assembled and how capabilities cooperate
- [Filesystem](./filesystem) — where the workspace physically lives (local / sandbox / shared store), `IsolationScope`, multi-user isolation
- [Context](./context) — `AgentState` and `Session` persistence, cross-node recovery
- [Memory](./memory) — how `MEMORY.md` / `memory/` are produced and maintained, compaction, eviction
- [Skills](./skill) — four-layer composition, self-learning loop, the `<available_skills>` block
- [Subagent](./subagent) — `subagents/` declarations, sync vs background, stream forwarding
- [Plan Mode](./plan-mode) — `plans/` files, read-only phase, HITL exit
