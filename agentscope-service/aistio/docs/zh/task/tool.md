# 工具系统

工具允许模型请求 Go 代码执行某项能力。

在 AgentScope Go 中，工具是一层稳定协议：模型看到工具名、描述和 JSON Schema；Go 侧收到结构化输入，执行后返回内容块。工具本身不负责决定模型后续动作，也不负责构造模型请求。这些由调用方或 `agent.Agent` 处理。

```{mermaid}
flowchart TD
    Tool["tool.Tool"] --> Schema["Name / Description / InputSchema"]
    Tool --> Permission["权限方法"]
    Tool --> Execute["Execute"]
    Schema --> Toolkit["tool.Toolkit"]
    Toolkit --> Model["模型可见工具 Schema"]
    Model --> ToolCall["message.ToolCallBlock"]
    ToolCall --> Toolkit
    Toolkit --> Execute
    Execute --> Result["message.ToolResultBlock"]
    Result --> Caller["调用方或 agent.Agent"]
```

## 工具接口

所有工具都会实现 `tool.Tool`。重要方法可以分成四类：

| 类别 | 方法 | 说明 |
| --- | --- | --- |
| 模型可见信息 | `Name`、`Description`、`InputSchema` | 转换成模型 function schema |
| 执行属性 | `IsConcurrencySafe`、`IsReadOnly`、`IsExternalTool`、`IsStateInjected` | 决定 Agent 是否可并发执行、是否需要权限、是否暂停等待外部执行 |
| 权限 | `CheckPermissions`、`MatchRule`、`GenerateSuggestions` | 给权限引擎提供工具特定判断 |
| 执行 | `Execute` | 返回一个 `ToolChunk` 流，最终累积为工具结果 |

大多数业务场景不需要手写完整 `Tool`。用 `tool.NewFunctionTool` 包装普通 Go 函数即可。

## 函数工具

使用 `tool.NewFunctionTool` 把普通 Go 函数包装成工具：

```go
greet, err := tool.NewFunctionTool(
	"Greet",
	"Return a greeting for one name.",
	map[string]any{
		"type": "object",
		"properties": map[string]any{
			"name": map[string]any{"type": "string"},
		},
		"required": []any{"name"},
	},
	func(_ context.Context, input map[string]any, _ *state.AgentState) (message.ContentBlockList, error) {
		name, _ := input["name"].(string)
		return message.ContentBlockList{message.NewTextBlock("hello " + name)}, nil
	},
	tool.WithFunctionReadOnly(true),
)
```

函数工具默认是并发安全的，但默认不是只读。只读工具应显式传入 `tool.WithFunctionReadOnly(true)`。需要读取或修改 `AgentState` 时，传入 `tool.WithFunctionStateInjected(true)`，并在 handler 中检查 state 是否为 `nil`。

流式工具可以使用 `tool.NewStreamFunctionTool`。它适合长任务、外部查询或需要分块返回结果的工具。

```go
streaming, err := tool.NewStreamFunctionTool(
	"StreamLines",
	"Return lines one by one.",
	map[string]any{"type": "object"},
	func(ctx context.Context, _ map[string]any, _ *state.AgentState) (<-chan tool.ToolChunk, error) {
		chunks := make(chan tool.ToolChunk)
		go func() {
			defer close(chunks)
			for _, line := range []string{"first", "second"} {
				select {
				case <-ctx.Done():
					return
				case chunks <- *tool.NewToolChunk(message.ContentBlockList{message.NewTextBlock(line)}):
				}
			}
		}()
		return chunks, nil
	},
	tool.WithFunctionReadOnly(true),
)
```

## Toolkit

`tool.Toolkit` 负责注册工具、暴露模型 Schema，并执行模型创建的工具调用：

```go
kit, err := tool.NewToolkit(greet)
if err != nil {
	panic(err)
}
schemas, err := kit.ToolSchemas()
if err != nil {
	panic(err)
}
result, err := kit.RunTool(ctx, toolCall, state.NewAgentState())
if err != nil {
	panic(err)
}
```

手动工具循环通常按以下顺序执行：

1. 调用 `kit.ToolSchemas()`，把 Schema 放入 `model.CallRequest.Tools`。
2. 模型返回 `message.ToolCallBlock`。
3. 调用 `kit.RunTool()` 执行工具。
4. 把 `message.ToolResultBlock` 作为后续消息发回模型。

```go
schemas, err := kit.ToolSchemas()
if err != nil {
	panic(err)
}

response, err := chat.Call(ctx, model.CallRequest{
	Messages: messages,
	Tools:    schemas,
})
if err != nil {
	panic(err)
}

for _, block := range response.GetContentBlocks("tool_call") {
	call := block.(*message.ToolCallBlock)
	result, err := kit.RunTool(ctx, call, state.NewAgentState())
	if err != nil {
		panic(err)
	}
	toolMessage, err := message.NewAssistantMessage("tool", message.ContentBlockList{
		message.NewToolResultBlock(call.ID, call.Name, message.ToolResultOutput{Blocks: result.Content}, result.State),
	})
	if err != nil {
		panic(err)
	}
	messages = append(messages, toolMessage)
}
```

这段循环适合精确控制模型轮次。需要自动重复“模型 -> 工具 -> 模型”时，把 `Toolkit` 传给 `agent.NewAgent`。

## 工具分组

工具很多时，可以用 `ToolGroup` 让模型按需激活一组工具。创建带分组的 `Toolkit` 后，框架会自动提供 `reset_tools` 元工具，用于更新 `AgentState.ToolContext.ActivatedGroups`。

```go
searchGroup, err := tool.NewGroup(
	"search",
	tool.WithGroupDescription("Search files in the sandbox."),
	tool.WithGroupInstructions("Use search tools before reading many files."),
	tool.WithGroupTools(grep, glob),
)
if err != nil {
	panic(err)
}

kit, err := tool.NewToolkitWithGroups([]tool.Tool{read}, searchGroup)
if err != nil {
	panic(err)
}
```

分组适合工具数量较多、每轮不希望把所有工具都暴露给模型的场景。工具很少时，直接使用 `NewToolkit` 更清楚。

## 内置工具

`tool/builtin` 包含：

- `Bash`
- `Edit`
- `Glob`
- `Grep`
- `Read`
- `Write`
- `ResetTools`

这些工具会接入权限系统。写入类操作默认需要询问，除非权限规则明确允许。

内置文件工具的模型可见名称是 `Bash`、`Edit`、`Glob`、`Grep`、`Read` 和 `Write`。这些工具可以直接注册，也可以通过 Sandbox 沙箱暴露。使用沙箱时更推荐后端实现的 `ListTools()` 或 `agent.WithWorkspace()`，因为沙箱会同时提供工作目录、系统提示词和 offload 能力。

## 任务工具

`tool/task` 提供任务管理工具：

- `TaskCreate`
- `TaskGet`
- `TaskList`
- `TaskUpdate`

任务工具会写入 `state.AgentState.TaskContext`。

任务工具适合让 Agent 维护显式任务列表。它不是项目管理系统，只是保存在当前 `AgentState` 中的运行期结构。

## Skill 工具

`tool/skill` 用于加载本地 `SKILL.md`。当项目需要把本地操作说明暴露给智能体时，可以使用该包。

Sandbox 沙箱中的 Skill 会被转换成系统提示词片段，并通过内置 `Skill` 查看工具读取完整说明。Skill 本身不是一个可以直接调用的业务函数。

## MCP 工具

MCP 工具由 `tool/mcp` 客户端包装成 `tool.Tool`。包装后，它们和函数工具、内置工具走同一套 `Toolkit`、权限和 Agent 执行路径。

```go
client, err := mcp.NewHTTPClient(
	"search",
	mcp.HTTPConfig{URL: "https://example.com/mcp"},
)
if err != nil {
	panic(err)
}

kit, err := mcp.NewDeferredToolkit(client)
if err != nil {
	panic(err)
}
```

MCP 适合复用外部工具生态。进程内 Go 函数能解决的问题，不必先拆成 MCP 服务。

## 权限与外部执行

Agent 执行工具前会调用权限引擎。只读工具更容易被自动放行；写入、Shell 和外部工具通常会触发确认或等待外部系统执行。

如果工具实现为外部执行工具，`Agent` 不会在当前 Go 进程内调用 `Execute`，而是发出 `RequireExternalExecutionEvent`。调用方执行外部任务后，再把 `ExternalExecutionResultEvent` 交回 Agent。

## 取舍建议

- 普通业务函数：优先使用 `NewFunctionTool`。
- 需要流式结果：使用 `NewStreamFunctionTool`。
- 需要文件和 Shell：通过 Sandbox 沙箱暴露内置工具。
- 工具数量很多：使用 `ToolGroup` 和 `reset_tools`。
- 外部系统已有 MCP 服务：使用 `tool/mcp`。
- 需要人工确认或审计：让 `agent.Agent` 统一走权限和事件流。
