# AgentScope Go

**A Go framework for building agent-oriented LLM applications.**

AgentScope Go provides the building blocks for agents that can talk to chat models, call tools, keep runtime state, manage permissions, and run against a local workspace. The implementation follows the Python AgentScope design where it fits Go idioms: packages are explicit, APIs are typed, and examples are small standalone Go modules.

## Requirements

- Go 1.26.3 or newer.
- A model provider API key when you run live model examples.
- `AI_DASHSCOPE_API_KEY` for the examples that use DashScope.

## Quick Start

1. Read [Installation](quickstart/installation.md).
2. Read [Key Concepts](quickstart/key-concepts.md).
3. Build a minimal agent with [Build an Agent](quickstart/agent.md).
4. Explore runnable examples in [Examples](task/examples.md).

## Minimal Example

```go
package main

import (
	"context"
	"fmt"
	"os"

	"github.com/yuluo-yx/agentscope-go/pkg/message"
	asmodel "github.com/yuluo-yx/agentscope-go/pkg/model"
	"github.com/yuluo-yx/agentscope-go/pkg/model/dashscope"
)

func main() {
	model, err := dashscope.NewChatModel(
		dashscope.NewCredential(os.Getenv("AI_DASHSCOPE_API_KEY")),
		"qwen3.7-max",
	)
	if err != nil {
		panic(err)
	}

	user, err := message.NewUserMessage("user", "Say hello in one short sentence.")
	if err != nil {
		panic(err)
	}

	response, err := model.Call(context.Background(), asmodel.CallRequest{
		Messages: []*message.Message{user},
	})
	if err != nil {
		panic(err)
	}

	if text := response.GetTextContent(); text != nil {
		fmt.Println(*text)
	}
}
```

## Project Layout

| Package | Purpose |
| --- | --- |
| `agent` | ReAct-style agent loop and middleware hooks |
| `loop` | Loop Engineering goals, budgets, verification, state, and events |
| `message` | Message and content block protocol |
| `model` | Chat model contracts and provider packages |
| `tool` | Tool interfaces, Toolkit, function adapters, and tool groups |
| `tool/builtin` | Bash, Edit, Glob, Grep, Read, Write, and ResetTools |
| `tool/mcp` | MCP client integration and MCP tool adapters |
| `tool/task` | TaskCreate, TaskGet, TaskList, and TaskUpdate |
| `tool/skill` | Local `SKILL.md` loader |
| `permission` | Permission modes, rules, decisions, and engine |
| `state` | AgentState, ToolContext, and TaskContext |
| `workspace` | Local workspace and offload helpers |

## Community

- GitHub: [yuluo-yx/agentscope-go](https://github.com/yuluo-yx/agentscope-go)

## License

AgentScope Go is released under the Apache License 2.0.
