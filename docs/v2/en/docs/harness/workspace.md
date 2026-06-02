---
title: "Workspace"
description: "Directory layout, what's injected into the system prompt, tools.json, multi-user isolation"
---

## Role

The workspace is `HarnessAgent`'s foundation. Persona, long-term memory, domain knowledge, subagent specs, skill definitions, session logs, subtask records, plan files, `tools.json` tool config — all expressed as **directories of Markdown/JSON** rather than scattered in code.

On every reasoning step, a few key files are auto-injected into the system prompt; memory, sessions, and task records are written back to predefined paths.

## A minimal workspace

```
.agentscope/workspace/
├── AGENTS.md          ← required: persona + behavior rules
├── MEMORY.md          ← optional: curated long-term memory (the agent maintains it)
├── knowledge/         ← optional: KNOWLEDGE.md + any reference files
├── memory/            ← auto-generated: daily fact log
├── skills/            ← optional: each subdir is one skill with a SKILL.md
├── subagents/         ← optional: subagent specs; filename = agent_id
├── plans/             ← auto-generated: plan files written in Plan Mode
├── tools.json         ← optional: MCP servers + tool allow/deny
└── agents/<agentId>/  ← auto-generated: session snapshots, logs, subtask records
    ├── context/<sessionId>/      session snapshots
    ├── sessions/                 conversation log (never compacted) + index
    └── tasks/                    subtask records
```

Only `AGENTS.md` is something you actually need to write (skipping it is fine — you just lose the persona section). The rest appears when you enable the corresponding capability:

- Enable memory compaction → `memory/` + `MEMORY.md`
- Drop subagent specs in → `subagents/`
- Install skills → `skills/`
- Enable Plan Mode → `plans/`

## What gets injected into the system prompt

Before each reasoning step, the framework assembles this structure and merges it into the first system message:

| Section | Source | Size limit |
|---------|--------|-----------|
| `## Session Context` | Generated from template (date, OS, workspace path, current `sessionId`) | unlimited |
| `## Workspace` usage guidance | Built-in template | unlimited |
| AGENTS context | Full `AGENTS.md` | unlimited |
| MEMORY context | `MEMORY.md` (bounded by token budget) | `maxContextTokens`, default ~8000 |
| Domain knowledge | `knowledge/KNOWLEDGE.md` + a listing of other files under `knowledge/` | full + path listing |
| Additional context files | Any files you add via `.additionalContextFile("X.md")` | full |

When `MEMORY.md` would overflow the remaining budget, it's truncated by character count with a trailing note: "truncated — use `memory_search` for older entries", nudging the model toward search.

## Configuration

```java
HarnessAgent agent = HarnessAgent.builder()
    .name("MyAgent")
    .model(model)
    .workspace(Paths.get(".agentscope/workspace"))   // omit → default ${user.dir}/.agentscope/workspace
    .additionalContextFile("SOUL.md")                // any workspace-relative path
    .additionalContextFile("PREFERENCES.md")
    .maxContextTokens(8000)                          // bounds MEMORY injection
    .build();
```

Minimum `AGENTS.md` skeleton:

```markdown
# MyAgent

You are an XX assistant, following these behavior guidelines.

## Behavior
- ...
- ...
```

## `tools.json` — tool allowlist + MCP

Drop `tools.json` at the workspace root and the framework reads it at build time:

```jsonc
{
  // allowlist: when non-empty, only listed tools are kept
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

Behavior:

- MCP servers are registered into the toolkit once at build time; the agent sees the tools they expose.
- `allow` / `deny` are applied **after** all tools (including Harness built-ins) are registered — so they also filter built-ins. Use with care.

Prefer programmatic config? You can inject directly on the builder, or disable file reading entirely with `disableToolsConfig()`.

## Multiple users sharing one workspace

Use `RuntimeContext.userId` to let one agent instance serve multiple users without crosstalk:

- A skill placed under `<userId>/skills/<name>/SKILL.md` is visible only to that user, and overrides the shared version.
- Where the per-user content actually lives depends on the filesystem mode:
  - Default (local): path prefix → `workspace/alice/...`
  - Remote KV: KV namespace prefix
  - Sandbox: sandbox state slot key

See [Filesystem](./filesystem) and [Context](./context) for details.

## Workspace-relative path rules

APIs like `additionalContextFile`, `writeUtf8WorkspaceRelative`, `memory_get` all accept **workspace-relative paths**. The framework does basic path-traversal checks so `../../etc/passwd` can't escape the workspace.

When you need to write files, **go through Harness's workspace manager**, not `java.nio.Files` — the latter writes to the wrong place under sandbox or remote filesystem modes. If you're writing during builder-time bootstrap (like `initWorkspaceIfAbsent` in the example) before any runtime context exists, `java.nio.Files` is fine there.

## Related Pages

- [Architecture](./architecture) — how the system prompt is assembled
- [Filesystem](./filesystem) — where the workspace physically lives (local / sandbox / shared store)
- [Memory](./memory) — how `MEMORY.md` and `memory/` are produced and maintained
- [Context](./context) — what lives under `agents/<agentId>/`
