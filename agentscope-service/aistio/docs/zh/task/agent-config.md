# 智能体配置

`agent.NewAgent` 使用显式 Go Option 配置智能体，不依赖 YAML 或 JSON 配置文件。

配置项按用途可以分成五类：模型重试、ReAct 循环、上下文管理、工具资源和中间件。简单 Agent 只需要模型、系统提示词和可选 `Toolkit`；只有在遇到失败重试、长上下文、工具环境或观测需求时，才需要继续增加配置。

```{mermaid}
flowchart TD
    NewAgent["agent.NewAgent"] --> Required["必需项<br/>name + system prompt + ChatModel"]
    NewAgent --> Runtime["运行期状态<br/>WithAgentState"]
    NewAgent --> Tools["工具来源<br/>WithToolkit / WithAdditionalToolkit"]
    NewAgent --> Sandbox["Sandbox 沙箱资源<br/>WithWorkspace / WithAgentResources"]
    NewAgent --> Loop["执行策略<br/>ModelConfig + ReActConfig + ContextConfig"]
    NewAgent --> Hooks["扩展能力<br/>WithMiddlewares"]
    Sandbox --> Resources["system prompt 片段<br/>Toolkit<br/>Offloader"]
    Tools --> Loop
    Runtime --> Loop
    Required --> Reply["Reply / ReplyStream"]
    Loop --> Reply
    Hooks --> Reply
    Resources --> Reply
```

## 构造函数

```go
agent, err := agent.NewAgent(
	"Friday",
	"You are concise and use tools when useful.",
	chatModel,
	agent.WithToolkit(kit),
	agent.WithAgentState(state.NewAgentState()),
)
```

`agent.NewAgent` 会校验名称和模型。名称用于事件、日志和消息发送者标识，建议使用稳定、简短的业务名称。

## Sandbox 沙箱资源

当智能体需要直接使用 Sandbox 沙箱时，使用 `agent.WithWorkspace(ctx, ws)`。
该 option 会初始化沙箱，把沙箱指令和 Skill 指令追加到 system
prompt，注册沙箱与 MCP 工具，并将沙箱作为上下文、工具结果和
DataBlock 的 offloader。

如果服务层已经调用 `workspace.BuildAgentResources` 生成资源对象，可以使用
`agent.WithAgentResources(resources)` 装配同一组 system prompt 片段、toolkit
和 offloader，避免调用方手动拆分资源对象。

选择建议：

| 场景 | 推荐方式 |
| --- | --- |
| 只注册少量函数工具 | `agent.WithToolkit(kit)` |
| 需要文件工具、Skill、MCP 和 offload | `agent.WithWorkspace(ctx, ws)` |
| 服务层要先审查沙箱资源 | `workspace.BuildAgentResources` + `agent.WithAgentResources` |
| 需要把多个工具来源合并 | `agent.WithToolkit` + `agent.WithAdditionalToolkit` |

## 模型配置

`ModelConfig` 控制重试行为和可选的兜底模型：

```go
agent.WithModelConfig(agent.ModelConfig{
	MaxRetries:    3,
	FallbackModel: fallbackModel,
})
```

`MaxRetries` 是每个模型的重试次数。配置了 `FallbackModel` 时，主模型所有重试失败后再尝试兜底模型。兜底模型应尽量兼容同一套消息和工具 Schema，否则失败会变得更难排查。

## ReAct 配置

`ReActConfig` 控制推理和行动循环：

```go
agent.WithReActConfig(agent.ReActConfig{
	MaxIters:     10,
	StopOnReject: true,
})
```

`MaxIters` 限制一次回复中的推理和工具调用轮数。工具能力越强，越应该设置合理上限。`StopOnReject` 为 `true` 时，工具被拒绝后 Agent 会停止继续行动；为 `false` 时，模型仍有机会基于拒绝结果给出替代回答。

## 上下文配置

`ContextConfig` 定义上下文压力阈值、摘要提示词、摘要 Schema 和工具结果截断长度。
默认策略链不是单一摘要策略，而是按顺序执行三类策略：先清理多模态数据和工具结果，再按剩余 token 做压力控制，最后按比例触发摘要压缩。

```go
agent.WithContextConfig(agent.ContextConfig{
	TriggerRatio:    0.8,
	ReserveRatio:    0.1,
	MaxTokens:       32000,
	ToolResultLimit: 50000,
})
```

```{mermaid}
flowchart TD
    Compress["Agent.CompressContext"] --> ToolResult["ToolResultContextStrategy<br/>DataBlock 卸载<br/>工具结果截断或卸载"]
    ToolResult --> HasMax{"MaxTokens > 0？"}
    HasMax -- 否 --> Done["完成轻量清理"]
    HasMax -- 是 --> Threshold["ThresholdContextStrategy<br/>记录上下文压力<br/>必要时触发摘要压缩或阻断"]
    Threshold --> Summary["SummaryContextStrategy<br/>超过 TriggerRatio 时<br/>摘要旧消息并保留近期上下文"]
    Summary --> Done
```

内置策略如下：

| 策略 | 触发条件 | 行为 | 适用场景 |
| --- | --- | --- | --- |
| `ToolResultContextStrategy` | 默认总会进入；有 offloader 或工具结果超过 `ToolResultLimit` 时产生实际效果 | 卸载 base64 `DataBlock`；截断或卸载超长 `ToolResultBlock` | 工具返回大文件、大文本或多模态数据 |
| `ThresholdContextStrategy` | `MaxTokens > 0`；默认绝对阈值只在 `MaxTokens` 大于 Warning 阈值时自动生效 | 写入 `AgentState.ContextStatus`；剩余 token 过低时摘要压缩；压缩后仍不足时返回 `ContextWindowError` | 长会话、需要提前发现上下文压力或阻断危险请求 |
| `SummaryContextStrategy` | `MaxTokens > 0`，且当前请求达到 `TriggerRatio * MaxTokens` | 保留近期上下文，把旧消息交给模型生成结构化摘要；有 offloader 时把被压缩消息写入沙箱或外部存储 | 希望自动压缩旧上下文，同时保留最近指令和未完成工具调用 |

`MaxTokens` 为 `0` 时，阈值策略和摘要策略都会跳过，默认只执行轻量清理。需要自动摘要压缩、上下文压力状态或阻断行为时，必须设置 `MaxTokens`。

`ThresholdContextStrategy` 会按剩余 token 数执行三档渐进响应：

| 阈值 | 默认值 | 行为 |
| --- | ---: | --- |
| Warning | `20000` | 记录 `warning` 状态 |
| Compact | `13000` | 自动摘要压缩旧上下文 |
| Blocking | `3000` | 压缩后仍不足时返回 `ContextWindowError` |

内置绝对阈值仅在 `MaxTokens` 大于 Warning 阈值时自动生效。较小模型窗口或测试场景需要三档行为时，应显式配置更小的
`ThresholdContextStrategy` 阈值。

`SummaryContextStrategy` 是基于比例的摘要策略。当前请求达到 `TriggerRatio * MaxTokens` 时，它会按 `ReserveRatio * MaxTokens` 尽量保留最近消息；未完成的工具调用、运行中的工具结果和需要继续确认的工具状态会被保留，避免压缩后丢失正在进行的动作。被压缩的旧消息会写入 `AgentState.Summary`；如果配置了 offloader 或 Sandbox 沙箱，还会把原始旧消息卸载到外部存储，并在摘要中留下引用。

替换策略链时可以自定义三档阈值。只要仍希望保留默认清理和摘要行为，就需要把对应内置策略也放回链路中：

```go
agent.WithContextStrategies(
	agent.NewToolResultContextStrategy(),
	agent.ThresholdContextStrategy{
		WarningThreshold:  20000,
		CompactThreshold:  13000,
		BlockingThreshold: 3000,
	},
	agent.NewSummaryContextStrategy(),
)
```

应用需要自定义存储、压缩策略或摘要写入方式时，可以实现 `agent.ContextStrategy` 并替换上下文策略链：

```go
agent.WithContextStrategies(customStrategy)
```

`WithContextStrategies` 是替换而不是追加。传入自定义策略后，默认的工具结果清理、阈值控制和摘要压缩不会自动保留；需要哪些内置行为，就显式传入哪些策略。只需要覆盖少量字段时，可以先使用 `agent.DefaultContextConfig()` 获取默认值。

配置建议：

- 短对话或固定小任务：保留默认 `MaxTokens=0`。
- 长会话：设置 `MaxTokens`，启用上下文压力跟踪和摘要压缩。
- 工具结果很长：调小或调大 `ToolResultLimit`，并配置 offloader。
- 需要强约束摘要格式：自定义 `SummarySchema` 和 `SummaryTemplate`。

## 中间件

使用 `agent.WithMiddlewares` 注册中间件。中间件可以拦截回复、推理、模型调用、工具执行和系统提示词构造。

可选 `middleware` 包提供 tracing middleware，不让 tracing 进入核心 `agent` 包路径：

```go
agent.WithMiddlewares(middleware.NewTracingMiddleware(tracer))
```

需要接入 OpenTelemetry 时，再显式使用 `middleware/otel` 子包。

中间件适合横切能力，例如 tracing、TTS、长期记忆和回复预算控制。业务工具仍应放在 `Toolkit` 中，不建议把业务动作藏在中间件里执行。
