# Tools

Tools let a model ask Go code to perform actions.

## Function Tools

Use `tool.NewFunctionTool` for custom Go functions:

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

## Toolkit

`tool.Toolkit` registers tools, exposes model schemas, and runs model-created tool calls:

```go
kit, err := tool.NewToolkit(greet)
schemas, err := kit.ToolSchemas()
result, err := kit.RunTool(ctx, toolCall, state.NewAgentState())
```

## Built-in Tools

`tool/builtin` includes:

- `Bash`
- `Edit`
- `Glob`
- `Grep`
- `Read`
- `Write`
- `ResetTools`

These tools integrate with the permission system. Write-like operations ask by default unless permission rules allow them.

## Task Tools

`tool/task` provides task management tools:

- `TaskCreate`
- `TaskGet`
- `TaskList`
- `TaskUpdate`

Task tools write to `state.AgentState.TaskContext`.

## Skill Tools

`tool/skill` loads local `SKILL.md` files. Use it when a project needs local operational instructions that can be surfaced to an agent.
