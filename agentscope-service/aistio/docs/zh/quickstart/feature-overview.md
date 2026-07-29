# 功能总览

AgentScope Go 提供一组用于构建 Go 智能体应用的类型化接口，覆盖模型调用、消息协议、工具调用、Agent 循环、权限控制、运行状态、Sandbox 沙箱、MCP 和中间件。各模块可以独立使用，也可以由 `agent.Agent` 统一编排。

模型服务、外部工具服务、运行环境和凭据由应用侧配置和管理。AgentScope Go 负责统一 Go 侧的调用边界、事件结构和扩展点。

## 使用路径

常见接入顺序：

1. 先用 `model` 和 `message` 调通一次模型调用。
2. 如果需要模型调用 Go 函数，再接入 `tool.Toolkit`。
3. 如果需要自动多轮推理和工具执行，再使用 `agent.Agent`。
4. 如果工具需要读写文件、加载 Skill 或卸载大内容，再接入 `workspace` 包中的沙箱实现。
5. 如果工具会修改文件、执行 Shell 或访问外部系统，再配置 `permission`。
6. 如果要对外暴露事件流、审计、TTS、记忆或预算控制，再接入 `middleware`。

简单问答服务通常只需要 `model.ChatModel`。需要完整 ReAct 循环时，再由 `agent.Agent` 编排模型、工具和事件。

```{mermaid}
flowchart TD
    Start["开始接入"] --> Model["model + message<br/>调通一次模型调用"]
    Model --> NeedTool{"需要模型调用<br/>Go 能力？"}
    NeedTool -- 否 --> ChatOnly["只保留 ChatModel"]
    NeedTool -- 是 --> Toolkit["接入 tool.Toolkit"]
    Toolkit --> NeedAgent{"需要多轮推理、<br/>权限或事件流？"}
    NeedAgent -- 否 --> Manual["手写模型和工具循环"]
    NeedAgent -- 是 --> Agent["使用 agent.Agent"]
    Agent --> NeedSandbox{"需要文件、Shell、<br/>Skill、MCP 或卸载？"}
    NeedSandbox -- 否 --> Permission["按工具风险配置 permission"]
    NeedSandbox -- 是 --> Sandbox["接入 Sandbox 沙箱"]
    Sandbox --> Permission
    Permission --> Middleware{"需要观测、记忆、<br/>TTS 或预算控制？"}
    Middleware -- 是 --> Hooks["添加 Middleware Hook"]
    Middleware -- 否 --> Done["完成最小架构"]
    Hooks --> Done
```

## 功能模块

| 模块 | 解决的问题 | 功能入口 | 相关文档 |
| --- | --- | --- | --- |
| 消息 | 统一表达 user、assistant、system、tool call、tool result 和多模态内容 | `message.Message`、`message.ContentBlock` | [消息](../task/message.md) |
| 模型 | 屏蔽不同供应商 SDK 的请求、响应、流式事件和工具 Schema 差异 | `model.ChatModel` | [模型集成](../task/model.md) |
| 语音 | 提供 TTS、批量 STT 和实时 STT Session，适合语音输入输出场景 | `audio/tts`、`audio/stt` | [模型集成](../task/model.md) |
| 工具 | 把 Go 函数、内置文件工具、任务工具、Skill 和 MCP 工具暴露给模型 | `tool.Tool`、`tool.Toolkit` | [工具系统](../task/tool.md) |
| 智能体 | 执行“模型推理 -> 权限检查 -> 工具执行 -> 结果回填”的循环 | `agent.Agent` | [构建智能体](agent.md) |
| 状态 | 保存会话、上下文、任务、工具缓存和上下文压力状态 | `state.AgentState` | [状态管理](../task/state.md) |
| 权限 | 决定工具调用是允许、拒绝、询问，还是交给自动分类器 | `permission.Engine` | [权限](../task/permission.md) |
| Sandbox 沙箱 | 给工具提供文件环境、Skill、MCP 记录和上下文卸载能力 | `workspace.Workspace` | [Sandbox 沙箱](../task/workspace.md) |
| MCP | 把 Model Context Protocol 服务包装成 AgentScope 工具 | `tool/mcp.Client` | [模型上下文协议](../task/mcp.md) |
| 中间件 | 在回复、模型调用、工具执行和系统提示词处插入扩展逻辑 | `agent.Middleware` | [Hook 系统](../task/hook.md) |

## 主要能力

### 模型调用

`model.ChatModel` 是最小可用接口。所有聊天模型供应商都实现 `Call`、`Stream` 和 `CountTokens`。这让上层代码不用关心 OpenAI、Anthropic、DashScope、Gemini 或 Ollama 的 SDK 细节。

```go
user, err := message.NewUserMessage("user", "Say hello in one sentence.")
if err != nil {
	panic(err)
}

response, err := chat.Call(ctx, model.CallRequest{
	Messages: []*message.Message{user},
})
if err != nil {
	panic(err)
}

if text := response.GetTextContent(); text != nil {
	fmt.Println(*text)
}
```

普通聊天、摘要、分类和结构化抽取通常只需要模型层。Agent、Sandbox 沙箱和权限系统适用于工具调用、隔离执行和多轮编排场景。

### 工具调用

工具系统把一段可执行能力表示为 `tool.Tool`。模型看到的是 JSON Schema，Go 侧执行的是 `Execute`。函数工具通过 `tool.NewFunctionTool` 包装；文件工具、任务工具和 MCP 工具也会进入同一套 `Toolkit`。

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

kit, err := tool.NewToolkit(greet)
if err != nil {
	panic(err)
}
```

工具调用有两种路径：调用方自行把 `kit.ToolSchemas()` 传给模型，并用 `kit.RunTool()` 执行模型返回的 `ToolCallBlock`；或者把 `Toolkit` 交给 `agent.Agent`，由 Agent 自动循环。

### 智能体循环

`agent.Agent` 适合需要多轮工具执行的任务。它会把系统提示词、历史消息、工具 Schema 和上下文摘要组装成模型请求，然后根据模型返回的事件执行工具。

Agent 不会绕过权限。模型要求执行工具时，Agent 会先调用权限引擎。需要用户确认时，`ReplyStream` 会发出 `RequireUserConfirmEvent`，调用方确认后再把结果事件交回 Agent。

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

err = runner.ReplyStream(ctx, user, func(event message.Event) error {
	if delta, ok := event.(*message.TextBlockDeltaEvent); ok {
		fmt.Print(delta.Delta)
	}
	return nil
})
if err != nil {
	panic(err)
}
```

需要完全控制每一轮模型请求时，可以手写“模型 -> 工具 -> 模型”的循环。Agent 用于集中处理事件、权限、上下文压缩和中间件。

### Sandbox 沙箱和文件工具

Sandbox 沙箱负责给智能体提供一个明确的执行环境。本地后端会创建 `data/`、`skills/` 和 `sessions/`；Docker 和 Agent Sandbox 后端则把同一组工具映射到隔离运行时。

```go
ws, err := local.NewWorkspace("/tmp/agentscope-sandbox")
if err != nil {
	panic(err)
}

runner, err := agent.NewAgent(
	"Friday",
	"Work inside the configured sandbox.",
	chat,
	agent.WithWorkspace(ctx, ws),
)
```

只有当工具需要文件系统、Shell、Skill、MCP 持久化或上下文卸载时，才需要 Sandbox 沙箱。纯模型调用和普通函数工具不需要它。

### 权限控制

权限系统解决的是“模型想做的事能不能做”。常见策略如下：

- 只读探索：使用 `permission.ModeExplore`，允许只读工具，拒绝写入。
- 人工确认：使用默认模式，让写入类工具触发确认事件。
- 后台执行：使用 `permission.ModeAuto`，配合分类器处理原本需要人工确认的操作。
- 已受控环境：使用 `permission.ModeBypass`，但仍要让工具自己的安全检查先执行。

权限规则应尽量具体。例如文件写入规则可以限制到一个沙箱目录，而不是直接允许所有写入。

## 取舍建议

### 什么时候只用 ChatModel

只用 `model.ChatModel` 的场景包括：普通聊天、摘要、分类、结构化抽取、服务端统一封装模型供应商。这样依赖最少，调用路径也最容易测试。

### 什么时候使用 Toolkit

当模型需要调用业务 Go 函数时，使用 `Toolkit`。它能导出模型可见的工具 Schema，也能执行模型返回的工具调用。调用方仍可以手写每一轮控制逻辑。

### 什么时候使用 Agent

当任务需要多轮工具调用、权限确认、事件流、中间件或上下文压缩时，使用 `Agent`。Agent 的抽象成本更高，但能把这些横切流程集中起来。

### 什么时候使用 Sandbox 沙箱

当工具需要文件、Shell、Skill、MCP 持久化或大内容卸载时，使用 Sandbox 沙箱。纯函数工具和普通模型调用不需要沙箱。

## 相关文档

- [安装](installation.md)
- [构建智能体](agent.md)
- [模型集成](../task/model.md)
- [工具系统](../task/tool.md)
- [Sandbox 沙箱](../task/workspace.md)
- [示例](../task/examples.md)
