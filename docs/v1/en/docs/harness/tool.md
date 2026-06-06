# Tooling

## Purpose

The Harness layer provides a default set of built-in tools sufficient to complete a full closed loop: reading and writing files, searching memory and sessions, delegating subagents, and optionally running a shell. No manual registration is needed — `HarnessAgent.build()` and `SubagentsHook` wire everything up together.

## Registration Path

```{mermaid}
graph LR
    Build[HarnessAgent.build] --> R1[FilesystemTool]
    Build --> R2[MemorySearchTool]
    Build --> R3[MemoryGetTool]
    Build --> R4[SessionSearchTool]
    Build -. backend is sandbox .-> R5[ShellExecuteTool]
    Hook[SubagentsHook.tools<br/>non-leaf with model] --> H1[AgentSpawnTool]
    Hook --> H2[TaskTool]
```

- **Direct registration**: `FilesystemTool` / `MemorySearchTool` / `MemoryGetTool` / `SessionSearchTool` are always registered; `ShellExecuteTool` is only registered when `backend instanceof AbstractSandboxFilesystem`.
- **Indirect registration**: `AgentSpawnTool` and `TaskTool` are returned by `SubagentsHook.tools()` and only appear when non-leaf and `model` is configured; in session mode, `agent_*` tools are renamed to `sessions_*`.

## Filesystem — `FilesystemTool`

Wraps `AbstractFilesystem`; paths are the backend's local paths.

| Tool | Purpose | Parameters |
|------|---------|------------|
| `read_file` | Read file content | `path`, `offset` (0-indexed), `limit` (0 = read all) |
| `write_file` | Create new file | `path`, `content` (errors if file already exists) |
| `edit_file` | Exact string replacement | `path`, `old_string` (unique by default), `new_string`, `replace_all` (default false) |
| `grep_files` | Search string in specified path (not regex) | `pattern`, `path`, `glob` (e.g. `*.java`) |
| `glob_files` | Find files by glob pattern | `pattern` (e.g. `**/*.md`), `path` |
| `list_files` | List directory | `path` |

## Memory — `MemorySearchTool` / `MemoryGetTool`

| Tool | Purpose | Parameters |
|------|---------|------------|
| `memory_search` | FTS5 full-text search, returns at most 30 results; falls back to keyword scan when MemoryIndex is unavailable | `query` |
| `memory_get` | Read a line range from a memory file, output with line numbers | `path` (workspace-relative), `startLine`, `endLine` (1-based) |

> Parameter names are camelCase (`startLine` / `endLine`), inconsistent with the snake_case used by filesystem tools.

## Session — `SessionSearchTool`

| Tool | Purpose | Parameters |
|------|---------|------------|
| `session_search` | Scan session JSONL for keywords | `query`, `agentId` (optional), `maxResults` (default 10) |
| `session_list` | List sessions for an agent; reads `sessions.json` first | `agentId` |
| `session_history` | Return the last N messages from a session | `agentId`, `sessionId`, `lastN` (default 20) |

> All parameter names are camelCase. `session_search` returns the "first 10 hits" across all `agents/<agentId>/sessions/*.jsonl`, **not** ranked by relevance.

## Subagent — `AgentSpawnTool`

| Tool | Purpose | Parameters |
|------|---------|------------|
| `agent_spawn` | Create a temporary subagent, optionally with an initial task | `agent_id` (required); `task` (optional, omit to create session only); `label` (optional alias); `timeout_seconds` (default 30, `0` = background, max 600) |
| `agent_send` | Send a follow-up message to an existing subagent | `agent_key` or `label` (one required); `message` (required); `timeout_seconds` (same rules) |
| `agent_list` | List currently active subagents | none |

```
agent_spawn agent_id="research-analyst"
            task="research topic X"
            timeout_seconds=60

# async
agent_spawn agent_id="research-analyst" task="full security audit" timeout_seconds=0
# → agent_key + task_id
```

In session mode, these three names become `sessions_spawn` / `sessions_send` / `sessions_list`.

## Background Tasks — `TaskTool`

| Tool | Purpose | Parameters |
|------|---------|------------|
| `task_output` | Get background task result | `task_id`, `block` (default true), `timeout` default 30000ms, max 600000ms |
| `task_cancel` | Cancel a task; no effect on terminal states | `task_id` |
| `task_list` | List tasks | `status_filter`: running / completed / failed / cancelled / all |

## Shell — `ShellExecuteTool` (Conditional)

Only registered when the backend is `AbstractSandboxFilesystem` (which includes `LocalFilesystemWithShell`). If you use a pure `LocalFilesystem` or `RemoteFilesystem`, this tool does not appear.

| Tool | Purpose | Parameters |
|------|---------|------------|
| `execute` | Calls backend `execute()`, returns stdout + exit code | `command`, `working_directory` (optional, prepended as `cd <dir> && <cmd>`), `timeout` (seconds, default 30) |

> **Note**: `@Tool` has no explicit `name` set, so the tool name defaults to the method name, meaning the LLM sees `execute`. A future rename to `shell_execute` is a small refactor — see [roadmap](./roadmap.md).

```
execute command="find . -name '*.java' | wc -l"
execute command="mvn test" timeout=300
execute command="git status" working_directory="app"   # becomes: cd app && git status
```

## Related Pages

- [Filesystem](./filesystem.md) — backend implementations and sandbox interface
- [Memory](./memory.md) — FTS5 and the two-layer memory behind `memory_search` / `memory_get`
- [Session](./session.md) — `WorkspaceSession` / `SessionTree` dual-track behind `session_*` tools
- [Subagent](./subagent.md) — scheduling and lifecycle of `agent_spawn` / `agent_send` / `task_*`
