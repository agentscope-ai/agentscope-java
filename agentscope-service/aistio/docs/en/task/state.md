# State

`state.AgentState` stores runtime information shared by agents and tools.

## Fields

| Field | Purpose |
| --- | --- |
| `SessionID` | Stable session identifier |
| `Context` | Conversation messages |
| `PermissionContext` | Permission mode, working directories, and rules |
| `ToolContext` | File read cache and activated tool groups |
| `TaskContext` | Task list used by task tools |
| `ContextStatus` | Latest context-window pressure level and threshold counters |

## Create State

```go
agentState := state.NewAgentState()
```

## Task Context

```go
task := state.NewTask("Write docs", "Create the MCP guide.", map[string]any{
	"area": "docs",
})
agentState.TaskContext.AddTask(task)
_ = agentState.TaskContext.UpdateTaskState(task.ID, state.TaskInProgress)
```

## Cloning

Use `Clone` when a caller needs an isolated copy:

```go
copy := agentState.Clone()
```

The clone performs deep copies for messages, permission rules, tool cache entries, tasks, and context status.
