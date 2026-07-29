# Key Concepts

AgentScope Go is organized around a few small contracts. You can use them separately or combine them through `agent.Agent`.

## Message

`message.Message` is the conversation unit. It has a role, a sender name, timestamps, metadata, and a list of content blocks.

Common blocks include:

- `TextBlock` for text.
- `DataBlock` for base64 or URL media.
- `ToolCallBlock` for model-requested tool calls.
- `ToolResultBlock` for tool outputs sent back to the model.

## Model

`model.ChatModel` is the common interface for chat providers:

```go
type ChatModel interface {
	Name() string
	Call(context.Context, CallRequest) (*ChatResponse, error)
	Stream(context.Context, CallRequest) (<-chan ChatResponse, error)
	CountTokens(CallRequest) (int, error)
}
```

Providers convert AgentScope messages and tool schemas to each SDK format.

## Tool

`tool.Tool` describes a callable capability. Tools expose a name, description, JSON schema, permission behavior, and an execution method.

Use `tool.NewToolkit` to register tools and to run model-created `ToolCallBlock` values.

## Agent

`agent.Agent` combines a model, tool provider, state, permission engine, and middleware hooks. It handles the loop:

1. Send messages to a model.
2. Read tool calls from the model response.
3. Check permissions.
4. Execute tools.
5. Append tool results.
6. Continue until the model returns final text.

## State

`state.AgentState` stores runtime state:

- conversation context,
- permission context,
- tool read cache,
- task context,
- current iteration counters.

## Workspace

`workspace/local.Workspace` provides a local execution environment for file tools, skill loading, context offload, and tool-result offload.
