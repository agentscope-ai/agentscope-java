# Messages

Messages are the protocol between users, models, tools, and agents.

## Roles

Use constructor helpers for common roles:

```go
system, err := message.NewSystemMessage("system", "You are concise.")
user, err := message.NewUserMessage("user", "Create a plan.")
assistant, err := message.NewAssistantMessage("assistant", "Use a workspace and call tools.")
```

`NewSystemMessage` finishes immediately because a system prompt is known at creation time. `NewAssistantMessage` represents model output and does not force a finish timestamp.

## Content Blocks

Text:

```go
block := message.NewTextBlock("hello")
```

Data:

```go
image := message.NewDataBlock(
	message.NewBase64Source("base64-data", "image/png"),
	message.WithDataBlockName("diagram.png"),
)
```

Tool result:

```go
result := message.NewToolResultBlock(
	"call-1",
	"Read",
	message.ToolResultOutput{Blocks: message.ContentBlockList{message.NewTextBlock("content")}},
)
```

`NewToolResultBlock` defaults to `ToolResultRunning`, matching Python's `ToolResultBlock.state` default. Pass an explicit state, such as `message.ToolResultSuccess`, when you are constructing a completed tool result to send back to a model.

Use content query helpers when you need plain text or filtered blocks:

```go
blocks := message.ContentBlockList{
	message.NewTextBlock("hello"),
	message.NewToolCallBlock("call-1", "Read", `{"path":"README.md"}`),
	message.NewTextBlock("world"),
}

text := blocks.GetTextContent()
if text != nil {
	fmt.Println(*text) // hello
	// world
}

joined := blocks.GetTextContent(" ")
if joined != nil {
	fmt.Println(*joined) // hello world
}

toolCalls := blocks.GetContentBlocks("tool_call")
if len(toolCalls) > 0 {
	fmt.Println(toolCalls[0].BlockID())
}
```

`GetTextContent()` matches the Python API default and joins multiple text blocks with a newline. Pass a custom separator only when a different join format is required.

## Conversation History

Keep model-ready history as a slice of messages:

```go
history := []*message.Message{system, user, assistant}
```

The `message` package clones content blocks when needed so callers can keep ownership of their input data.
