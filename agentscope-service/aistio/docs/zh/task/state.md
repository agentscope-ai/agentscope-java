# 状态管理

`state.AgentState` 保存智能体和工具共享的运行期信息。

状态对象表示一个 Agent 会话的当前视图。它包含对话历史、权限上下文、工具缓存、任务列表和上下文窗口状态。不要在互相独立的用户会话之间共享同一个 `AgentState`。

```{mermaid}
flowchart LR
    Session["一个用户会话"] --> AgentState["state.AgentState"]
    AgentState --> Context["Context<br/>对话历史"]
    AgentState --> Permission["PermissionContext<br/>权限模式和规则"]
    AgentState --> Tool["ToolContext<br/>工具缓存和启用组"]
    AgentState --> Task["TaskContext<br/>任务列表"]
    AgentState --> Window["ContextStatus<br/>上下文压力"]
```

## 字段

| 字段 | 用途 |
| --- | --- |
| `SessionID` | 稳定会话标识 |
| `Context` | 对话消息 |
| `PermissionContext` | 权限模式、工作目录和规则 |
| `ToolContext` | 文件读取缓存和已启用工具组 |
| `TaskContext` | 任务工具使用的任务列表 |
| `ContextStatus` | 最近一次上下文窗口压力等级和阈值计数 |

## 创建状态

```go
agentState := state.NewAgentState()
```

创建 `agent.Agent` 时如果不传 `WithAgentState`，Agent 会创建自己的默认状态。需要恢复会话、预置权限规则或共享任务上下文时，再显式传入状态。

## 任务上下文

```go
task := state.NewTask("Write docs", "Create the MCP guide.", map[string]any{
	"area": "docs",
})
agentState.TaskContext.AddTask(task)
_ = agentState.TaskContext.UpdateTaskState(task.ID, state.TaskInProgress)
```

## 克隆

调用方需要隔离副本时使用 `Clone`：

```go
copy := agentState.Clone()
```

克隆会深拷贝消息、权限规则、工具缓存条目、任务和上下文状态。

## 使用建议

- 每个用户会话使用独立 `AgentState`。
- 需要持久化时，只保存业务需要恢复的字段。
- 工具需要修改任务或缓存时，通过 `state.AgentState` 明确传入。
- 并发请求不要同时修改同一个状态对象，除非调用方自己做同步。
