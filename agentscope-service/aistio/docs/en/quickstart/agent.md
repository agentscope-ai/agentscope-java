# Build an Agent

This page shows the shape of a minimal agent. Use the standalone examples for runnable copies.

## Create a Chat Model

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

## Create a Tool

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

## Register Tools

```go
kit, err := tool.NewToolkit(greet)
if err != nil {
	panic(err)
}
```

## Run the Agent Loop

The agent package can drive the full loop. For direct control, call the model with tool schemas, execute the returned tool call through `Toolkit`, then send a `ToolResultBlock` back to the model.

```go
schemas, err := kit.ToolSchemas()
if err != nil {
	panic(err)
}

user, err := message.NewUserMessage("user", "Use the Greet tool to greet Go.")
if err != nil {
	panic(err)
}

response, err := chat.Call(context.Background(), model.CallRequest{
	Messages: []*message.Message{user},
	Tools:    schemas,
})
if err != nil {
	panic(err)
}
```

## Runnable Examples

- `example/agent/basic`: an end-to-end ReAct flow with a DashScope ChatModel.
- `example/agent/configuration`: model fallback, ReAct limits, and context cleanup.
- `example/agent/external`: pause and resume around external tool execution.
- `example/agent/hooks`: middleware hooks around reply, reasoning, model call, acting, and system prompt.
- `example/agent/permission`: permission confirmation and resume for a pending tool call.
- `example/tool/function`: a function tool with a DashScope tool-call loop.
- `example/tool/mcp`: an MCP tool registered through `tool.Toolkit`.
