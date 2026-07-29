# 消息

消息是用户、模型、工具和智能体之间的协议。

AgentScope Go 不把模型响应直接表示成字符串，而是表示成消息和内容块。这样同一套结构可以同时承载文本、多模态数据、工具调用、工具结果和流式事件。

## 角色

常见角色可以使用构造函数创建：

```go
system, err := message.NewSystemMessage("system", "You are concise.")
user, err := message.NewUserMessage("user", "Create a plan.")
assistant, err := message.NewAssistantMessage("assistant", "Use a sandbox and call tools.")
```

`NewSystemMessage` 会立即标记完成，因为系统提示词在创建时已经确定。`NewAssistantMessage` 表示模型输出，不会强制写入完成时间。

## 内容块

常见内容块如下：

| 内容块 | 用途 |
| --- | --- |
| `TextBlock` | 普通文本 |
| `ThinkingBlock` | 模型推理或思考内容 |
| `HintBlock` | 系统、中间件或记忆注入的提示 |
| `DataBlock` | Base64 或 URL 多模态数据 |
| `ToolCallBlock` | 模型请求执行工具 |
| `ToolResultBlock` | 工具执行结果 |

文本：

```go
block := message.NewTextBlock("hello")
```

数据：

```go
image := message.NewDataBlock(
	message.NewBase64Source("base64-data", "image/png"),
	message.WithDataBlockName("diagram.png"),
)
```

工具结果：

```go
result := message.NewToolResultBlock(
	"call-1",
	"Read",
	message.ToolResultOutput{Blocks: message.ContentBlockList{message.NewTextBlock("content")}},
)
```

`NewToolResultBlock` 默认状态是 `ToolResultRunning`。构造已经完成的工具结果并回填给模型时，再显式传入 `message.ToolResultSuccess` 等真实状态。

需要从内容块列表中读取文本或筛选内容块时，可以使用统一的查询方法：

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

`GetTextContent()` 默认会用换行符拼接多个文本块；只有需要自定义拼接格式时才传入分隔符。

工具调用：

```go
call := message.NewToolCallBlock("call-1", "Read", `{"file_path":"README.md"}`)
result := message.NewToolResultBlock(
	call.ID,
	call.Name,
	message.ToolResultOutput{Blocks: message.ContentBlockList{message.NewTextBlock("file content")}},
	message.ToolResultSuccess,
)
```

`ToolCallBlock.Input` 是 JSON 字符串。`Toolkit` 执行工具前会把它解析成 `map[string]any`。

## 对话历史

模型可用的历史消息通常保存为切片：

```go
history := []*message.Message{system, user, assistant}
```

`message` 包会在需要时克隆内容块，调用方可以继续持有自己的输入数据。

## 流式事件

Agent 的 `ReplyStream` 会返回事件，而不是直接返回文本。事件会被应用到当前 assistant 消息上，最终形成完整回复。

```go
err := runner.ReplyStream(ctx, user, func(event message.Event) error {
	if delta, ok := event.(*message.TextBlockDeltaEvent); ok {
		fmt.Print(delta.Delta)
	}
	return nil
})
```

只需要最终文本的服务可以使用 `Agent.Reply`。前端实时展示、工具状态和确认弹窗适合使用 `ReplyStream`。
