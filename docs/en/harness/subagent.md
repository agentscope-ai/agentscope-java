# Subagent

## Purpose

Subagents let the parent agent delegate tasks that are "independently handled, context-heavy, or parallelizable", preventing the main thread from bloating.  
Each subagent is a temporary `HarnessAgent` (or remote stub) instance with its own sub-session; results are returned via tool output.

---

## When Subagents Are Enabled

`HarnessAgent.build()` only loads subagent capability when all of the following are true:

- The current agent is **not** a leaf subagent
- `disableSubagents()` has not been called
- `model` is configured

When satisfied, `SubagentsHook` (priority=80) is registered and exposes:

- `agent_spawn` / `agent_send` / `agent_list`
- `task_output` / `task_cancel` / `task_list`

On every `PreReasoningEvent` turn, `SubagentsHook` injects into SYSTEM:

- Subagent usage rules
- List of currently available `agent_id`s
- Summary of async tasks in the current session (up to 10)

---

## Declaration Sources

`buildSubagentEntries(...)` merges four sources:

1. Built-in `general-purpose`
2. Programmatic declarations: `builder.subagent(SubagentDeclaration)`
3. File declarations: `workspace/subagents/*.md` (loaded non-recursively by `AgentSpecLoader`)
4. Custom factories: `builder.subagentFactory(name, factory)`

---

## Declaration Model (`SubagentDeclaration`)

`SubagentDeclaration` supports 3 mutually exclusive source modes:

1. **Definition workspace mode**
   - `workspace(path)` points to a definition directory (typically containing `AGENTS.md`)
2. **Inline mode**
   - `inlineAgentsBody(...)` is used directly as the system prompt base
3. **Remote HTTP mode**
   - `url(...)` + optional `headers(...)`, execution delegated remotely via the task protocol

Mutual exclusion constraints (validated on `build()`):

- `url` cannot coexist with `workspace` or non-empty `inlineAgentsBody`
- `workspace` and non-empty `inlineAgentsBody` cannot coexist

---

## Runtime Workspace Five-Row Decision Table

`WorkspaceMode` determines the runtime workspace root:

| Case | sysPrompt base source | Runtime workspace |
|------|----------------------|-------------------|
| Built-in `general-purpose` (always SHARED) | No additional base (only Subagent Context appended; `AGENTS.md` still injected by WorkspaceContextHook) | `mainWorkspace` |
| `workspace.path` + `ISOLATED` | `<workspace.path>/AGENTS.md` (empty if absent) | `workspace.path` |
| `workspace.path` + `SHARED` | `<workspace.path>/AGENTS.md` (empty if absent) | `mainWorkspace` |
| No `workspace.path` + `ISOLATED` (default) | `inlineAgentsBody` / markdown body | `mainWorkspace/agents/<name>/workspace` (auto-created) |
| No `workspace.path` + `SHARED` | `inlineAgentsBody` / markdown body | `mainWorkspace` |

Notes:

- `tools` is an **allowlist for inherited tools**: it only filters the parent toolkit, and does not affect tools the subagent auto-registers locally
- Multiple declarations can reuse the same definition workspace
- `workspace.path` relative paths are resolved via `mainWorkspace.resolve(...).normalize()`

---

## Declaration Files (`workspace/subagents/<id>.md`)

The filename (without `.md`) is the `agent_id`; `name` is not read from front matter.

```markdown
---
description: Code review expert
workspace:
  mode: isolated               # isolated | shared, defaults to isolated
  path: ./defs/reviewer        # optional; relative to mainWorkspace or absolute
model: openai:gpt-4o-mini      # optional
maxIters: 8                    # optional, default 10
tools: [read_file, grep_files] # optional
---

You are a subagent focused on code review.
```

Parsing rules (`AgentSpecLoader`):

- Required: `description`
- Only scans the **first level** of `subagents/` directory for `.md` files (non-recursive)
- If `workspace.path` is set and body is non-empty: logs a warning; body is ignored
- Markdown declarations currently do not parse `url/headers` (remote declarations should use the programmatic API)

---

## Programmatic Configuration

```java
HarnessAgent.builder()
    .name("orchestrator")
    .model(model)
    .workspace(workspace)
    .subagent(SubagentDeclaration.builder()
        .name("reviewer")
        .description("Code review expert")
        .workspace(Path.of("./defs/reviewer"))
        .workspaceMode(WorkspaceMode.ISOLATED)
        .model("qwen3-max")
        .maxIters(8)
        .tools(List.of("read_file", "grep_files"))
        .build())
    .subagent(SubagentDeclaration.builder()
        .name("remote-researcher")
        .description("Remote research subagent")
        .url("http://agent-task-server:8080")
        .headers(Map.of("Authorization", "Bearer xxx"))
        .build())
    .build();
```

---

## Built-in `general-purpose`

The built-in `general-purpose` requires no declaration file and is always included in the entry list.  
Its goal is to "mirror the parent agent's capabilities":

- Shares the main workspace (`SHARED` semantics)
- Inherits and mirrors the parent agent's:
  - toolkit (parent tools)
  - hooks
  - execution config
  - compaction / toolResultEviction
  - additional context files / maxContextTokens
  - various disable flags
- Always a leaf subagent (cannot further spawn subagents)

---

## Recursion Prevention and Depth Safety

Two safety mechanisms:

1. All subagents generated via declarations or built-in are given `asLeafSubagent()` — leaf agents do not register `SubagentsHook`
2. `AgentSpawnTool` also has a dynamic depth cap: `MAX_SPAWN_DEPTH = 3`

---

## RuntimeContext Propagation

When `agent_spawn` / `agent_send` invokes a subagent:

- The sub-session `session_id` is a new value (`sub-<uuid>`)
- `userId` is propagated from the parent `RuntimeContext` to the child `RuntimeContext`

This maintains consistent USER-dimension isolation keys (e.g., scenarios where namespace/sandbox isolation depends on `userId`).

---

## Tool Reference and Key Parameters

| Tool | Purpose | Key Parameters |
|------|---------|---------------|
| `agent_spawn` | Create a subagent; optionally execute the first task sync or async | `agent_id` required; `task` optional; `label` optional; `timeout_seconds` default 30, `0` = background, max 600 |
| `agent_send` | Send a follow-up message to an existing subagent | `agent_key` or `label` (one required); `message` required; `timeout_seconds` same rules |
| `agent_list` | List currently active subagents | none |
| `task_output` | Query / wait for background task result | `task_id`; `block` (default true, use false to poll status); `timeout` default 30000ms, max 600000ms |
| `task_cancel` | Cancel a task | `task_id` |
| `task_list` | List tasks in the current session | `status_filter` (running/completed/failed/cancelled/all) |

Notes:

- The `agent_key` for `agent_send` must be the complete `agent_key: ...` from the `agent_spawn` return value (not `agent_id` / `session_id` / `task_id`)
- Do not immediately poll after creating an async task; prefer returning to the user first, then using `task_output(block=false)` or `task_list` to check latest status

---

## Async Task Lifecycle and Storage

By default, the parent agent uses `WorkspaceTaskRepository` (unless overridden with `taskRepository(...)`).

Lifecycle (simplified):

1. `putTask(...)` writes `TaskRecord(PENDING)` to workspace
2. Submits local execution future (local or remote)
3. Updates to `RUNNING` during execution
4. Writes `COMPLETED / FAILED / CANCELLED` on completion

Storage layering:

- In-memory layer: `localTasks` (node-local acceleration handle, lost on restart)
- Persistent layer: `agents/<parentAgentId>/tasks/<sessionId>.json` (source of truth)

---

## Distributed Semantics

- Task execution is sticky to the creating node, but any node can read state through the workspace
- `task_output(block=true)` degrades gracefully in cross-node scenarios and does not block indefinitely
- `task_cancel` persists `cancelRequested=true`; the executing node polls this flag and aborts
- The orphan sweeper marks local tasks with no heartbeat for a long time as `FAILED` (remote transport tasks are not subject to this check)

Relationship to filesystem mode:

| Mode | `agents/<agentId>/tasks/` path visibility |
|------|------------------------------------------|
| `RemoteFilesystemSpec` | Routed to shared remote storage, visible to multiple nodes |
| `SandboxFilesystemSpec` | Goes through sandbox filesystem and sandbox state persistence |
| `LocalFilesystemSpec` / local default | Visible on local machine only |

---

## Remote Subagent Behavior

When a declaration has `url(...)` configured:

- The factory returns a `RemoteSubagentStub` (placeholder, performs no local reasoning)
- Actual execution is delegated to a remote task HTTP service via `TaskRunSpec.RemoteTaskRunSpec` + `AgentProtocolTaskClient`
- Supports both synchronous (`timeout_seconds > 0`) and asynchronous (`timeout_seconds = 0`) modes

---

## Practical Advice

1. Write `description` clearly — "when to use / output format / prohibited actions" — this is the key signal the parent model uses to decide whether to delegate
2. Subagent `maxIters` is typically set smaller than the parent agent to avoid sub-threads consuming excessive tokens
3. After session compaction or recovery, first use `task_list()` to restore full task state before making single-task queries

---

## Related Pages

- [Tool](./tool.md)
- [Workspace](./workspace.md)
- [Architecture](./architecture.md)
- [Subagent Streaming](./streaming.md)
