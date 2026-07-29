# 构建智能体

`agent.Agent` 负责统一多轮 ReAct 流程：模型生成工具调用，Agent 检查权限，工具执行后把结果回填给模型，最后输出回复。一次性模型调用通常停留在 `model.ChatModel` 层；涉及多轮工具调用、权限确认或事件流时，再接入 Agent。

```{mermaid}
flowchart TD
    User["用户输入"] --> Agent["agent.Agent"]
    Agent --> Model["ChatModel"]
    Model --> Reply{"模型是否返回<br/>ToolCallBlock？"}
    Reply -- 否 --> Final["最终回复"]
    Reply -- 是 --> Permission["权限检查"]
    Permission --> Decision{"权限决策"}
    Decision -- allow --> Toolkit["Toolkit 执行工具"]
    Decision -- ask --> Confirm["RequireUserConfirmEvent"]
    Decision -- deny --> Denied["把拒绝结果写回上下文"]
    Confirm --> UserDecision["UserConfirmResultEvent"]
    UserDecision --> Permission
    Toolkit --> Result["ToolResultBlock"]
    Result --> Agent
    Denied --> Agent
    Agent --> Model
```

## 创建聊天模型

```go
chat, err := dashscope.NewChatModel(
	dashscope.NewCredential(os.Getenv("AI_DASHSCOPE_API_KEY")),
	"qwen3.7-max",
	dashscope.WithStream(false),
)
if err != nil {
	panic(err)
}
```

模型只需要实现 `model.ChatModel`。可以使用内置供应商，也可以在测试中使用自定义测试模型。

## 创建工具

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
if err != nil {
	panic(err)
}
```

自定义函数工具默认会触发权限询问。示例中的 `tool.WithFunctionReadOnly(true)` 表示该工具没有副作用，在 `explore`、`auto` 等模式下更容易被放行。

## 注册工具

```go
kit, err := tool.NewToolkit(greet)
if err != nil {
	panic(err)
}
```

`Toolkit` 会把工具转换为模型可见的 function schema，也负责执行 `ToolCallBlock`。`Toolkit` 可以交给 Agent 编排，也可以由调用方手写模型工具循环。

## 运行智能体循环

`agent` 包可以驱动完整循环。最常见的组合方式是把模型、工具和状态一起传给 `agent.NewAgent`：

```go
runner, err := agent.NewAgent(
	"Friday",
	"Use tools only when they help.",
	chat,
	agent.WithToolkit(kit),
	agent.WithAgentState(state.NewAgentState()),
)
if err != nil {
	panic(err)
}

user, err := message.NewUserMessage("user", "Use the Greet tool to greet Go.")
if err != nil {
	panic(err)
}

reply, err := runner.Reply(context.Background(), user)
if err != nil {
	panic(err)
}

if text := reply.GetTextContent(); text != nil {
	fmt.Println(*text)
}
```

需要把过程实时返回给上游时，使用 `ReplyStream`：

```go
err = runner.ReplyStream(context.Background(), user, func(event message.Event) error {
	switch e := event.(type) {
	case *message.TextBlockDeltaEvent:
		fmt.Print(e.Delta)
	case *message.ToolCallStartEvent:
		fmt.Printf("calling tool: %s\n", e.ToolCallName)
	case *message.RequireUserConfirmEvent:
		fmt.Printf("waiting for user confirmation: %d call(s)\n", len(e.ToolCalls))
	}
	return nil
})
if err != nil {
	panic(err)
}
```

`Reply` 是 `ReplyStream` 的便捷封装。服务端需要返回流式事件时优先用 `ReplyStream`；脚本或批处理场景可以用 `Reply`。

## 处理权限确认

当工具调用需要用户确认时，`ReplyStream` 会发出 `RequireUserConfirmEvent` 并暂停。调用方把用户决策转换成 `UserConfirmResultEvent` 后，再调用 `Reply` 或 `ReplyStream` 继续执行。

```go
confirmEvent := message.NewUserConfirmResultEvent(confirm.ReplyID(), []message.ConfirmResult{{
	Confirmed: true,
	ToolCall:  confirm.ToolCalls[0],
	Rules:     confirm.ToolCalls[0].SuggestedRules,
}})

reply, err := runner.Reply(context.Background(), confirmEvent)
```

不需要人工确认时，可以用权限规则预先允许特定工具输入，或在受控环境中使用更合适的权限模式。权限检查不应为绕过确认而直接删除。

## 组合 Sandbox 沙箱

当智能体需要文件工具、Skill、MCP 或上下文卸载时，可以把 Sandbox 沙箱直接交给 Agent：

```go
ws, err := local.NewWorkspace("/tmp/agentscope-sandbox")
if err != nil {
	panic(err)
}

runner, err := agent.NewAgent(
	"Friday",
	"Work inside the configured sandbox.",
	chat,
	agent.WithWorkspace(context.Background(), ws),
)
```

`WithWorkspace` 会初始化沙箱，追加沙箱指令，注册沙箱工具和 MCP 工具，并把沙箱作为上下文和工具结果的 offloader。纯函数工具不需要沙箱。

## 可运行示例

- `example/agent/basic`：使用 DashScope ChatModel 演示端到端 ReAct 流程。
- `example/agent/configuration`：演示 model fallback、ReAct 限制和上下文清理。
- `example/agent/external`：演示外部工具执行的暂停与恢复。
- `example/agent/hooks`：演示 reply、reasoning、model call、acting 和 system prompt middleware Hook。
- `example/agent/permission`：演示权限确认和等待中工具调用的恢复。
- `example/agent/team`：演示进程内 leader/worker Agent team tools 与 inbox 投递。
- `example/tool/function`：演示函数工具和 DashScope 工具调用循环。
- `example/tool/mcp`：演示 MCP 工具通过 `tool.Toolkit` 注册和执行。
