# Hook 系统

AgentScope Go 通过 `agent.Middleware` 暴露 Hook 能力。一个中间件可以实现一个或多个 Hook 接口。

Hook 用于扩展 Agent 生命周期中的横切逻辑，例如观测、审计、提示词改写、TTS、长期记忆或预算控制。它不适合替代工具系统：如果模型需要显式调用某个业务能力，应把它做成 `tool.Tool`，而不是藏在中间件里。

```{mermaid}
flowchart TD
    Reply["ReplyMiddleware<br/>完整回复入口"] --> SystemPrompt["SystemPromptMiddleware<br/>修改系统提示词"]
    SystemPrompt --> ModelCall["ModelCallMiddleware<br/>拦截模型请求"]
    ModelCall --> Reasoning["ReasoningMiddleware<br/>观察模型输出事件"]
    Reasoning --> NeedTool{"模型请求工具？"}
    NeedTool -- 否 --> Final["返回回复事件"]
    NeedTool -- 是 --> Acting["ActingMiddleware<br/>拦截工具执行"]
    Acting --> Tool["tool.Tool"]
    Tool --> Reasoning
```

## Hook 类型

| 接口 | 用途 |
| --- | --- |
| `ReplyMiddleware` | 拦截完整回复流程 |
| `ReasoningMiddleware` | 拦截模型推理事件 |
| `ActingMiddleware` | 拦截工具执行 |
| `ModelCallMiddleware` | 拦截原始模型调用 |
| `SystemPromptMiddleware` | 在模型调用前修改系统提示词 |

选择建议：

- 想看完整回复过程：实现 `ReplyMiddleware`。
- 想观察模型输出事件：实现 `ReasoningMiddleware`。
- 想统计或审计工具执行：实现 `ActingMiddleware`。
- 想修改模型、请求参数或流式响应：实现 `ModelCallMiddleware`。
- 想追加系统提示词：实现 `SystemPromptMiddleware`。

## System Prompt Hook

```go
type PromptNote struct{}

func (PromptNote) MiddlewareName() string { return "prompt-note" }

func (PromptNote) OnSystemPrompt(ctx context.Context, accessor agent.AgentAccessor, prompt string) (string, error) {
	return prompt + "\nUse concise answers.", nil
}
```

构造智能体时注册：

```go
agent.WithMiddlewares(PromptNote{})
```

## 执行顺序

中间件按注册顺序执行。中间件可以调用下一个处理器，检查结果，替换结果，或返回错误。

注册顺序会影响结果。靠前的中间件先接收输入，也更早包装后续 handler。多个中间件都修改系统提示词时，应把通用约束放前面，把更具体的业务约束放后面。

## 可选 tracing

使用 `github.com/yuluo-yx/agentscope-go/pkg/middleware` 获取 tracing middleware：

```go
agent.WithMiddlewares(middleware.NewTracingMiddleware(tracer))
```

`TracingMiddleware` 只依赖很小的 `middleware.Tracer` 接口。核心 `agent` 包不导入 OpenTelemetry。需要接入 OpenTelemetry 的应用可以通过 `github.com/yuluo-yx/agentscope-go/pkg/middleware/otel` 适配 tracer。

## 可选 TTS

`middleware.NewTTSMiddleware` 可以为 assistant reply 流追加合成音频：

```go
speech, err := dashscopetts.NewModel(
	dashscopetts.NewCredential(os.Getenv("AI_DASHSCOPE_API_KEY")),
	"qwen3-tts-flash",
)
agent.WithMiddlewares(middleware.NewTTSMiddleware(speech))
```

该 middleware 会保留原始文本事件。对于批处理模型，它会收集一个文本块，并在文本块结束后追加
`DATA_BLOCK_START`、`DATA_BLOCK_DELTA` 和 `DATA_BLOCK_END`。实时模型会通过
`Push` 接收文本增量，并在文本块结束时用空 `tts.Request` 做收尾读取。

## 可选长期记忆

`middleware.NewLongTermMemoryMiddleware` 通过很小的 `MemoryStore` 接口接入长期记忆存储：

```go
memory := middleware.NewLongTermMemoryMiddleware(
	"alice",
	store,
	middleware.WithMemoryMode(middleware.MemoryModeBoth),
)
agent.WithMiddlewares(memory)
```

`static_control` 会在回复开始时检索相关记忆并注入 `HintBlock`，在回复结束后写回本轮输入和输出。
`agent_control` 会暴露 `search_memory` 和 `add_memory` 两个工具，并在系统提示词中补充工具使用说明。
`both` 同时启用两种方式。Mem0 或其他后端可以通过实现 `MemoryStore` 适配进来。

## 可选回复预算控制

`middleware.NewReplyBudgetControlMiddleware` 会按回复维度累计 `ModelCallEndEvent` 中的 token 使用量：

```go
budget := middleware.NewReplyBudgetControlMiddleware(
	10000,
	middleware.WithReplyBudgetWeights(1, 2),
)
agent.WithMiddlewares(budget)
```

累计成本达到预算后，middleware 会在下一次 reasoning 前注入 wrap-up 提示，并在后续模型调用中把
`tool_choice` 强制设为 `none`，让模型停止继续调用工具并输出最终回复。回复结束后预算状态会自动清理。

## 取舍建议

- 业务动作：做成工具。
- 观测、审计、预算、记忆和提示词增强：做成中间件。
- 只影响单个模型请求参数：优先使用模型供应商 Option；需要按上下文动态修改时再用 `ModelCallMiddleware`。
- 需要对外返回执行过程：使用 `ReplyStream`，中间件只负责补充或观察事件。
