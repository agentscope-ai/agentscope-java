# Agent Configuration

`agent.NewAgent` accepts explicit Go options instead of a YAML or JSON agent configuration file.

## Constructor

```go
agent, err := agent.NewAgent(
	"Friday",
	"You are concise and use tools when useful.",
	chatModel,
	agent.WithToolkit(kit),
	agent.WithAgentState(state.NewAgentState()),
)
```

## Workspace Resources

Use `agent.WithWorkspace(ctx, ws)` when an Agent should consume a workspace
directly. The option initializes the workspace, appends workspace instructions
and skill instructions to the system prompt, registers workspace and MCP tools,
and uses the workspace as the context/tool-result/data-block offloader.

When a service layer has already called `workspace.BuildAgentResources`, pass
the result with `agent.WithAgentResources(resources)` to wire the same system
prompt fragment, toolkit, and offloader without manually splitting the resource
object.

## Model Configuration

`ModelConfig` controls retry behavior and the optional fallback model:

```go
agent.WithModelConfig(agent.ModelConfig{
	MaxRetries:    3,
	FallbackModel: fallbackModel,
})
```

## ReAct Configuration

`ReActConfig` controls the reasoning and acting loop:

```go
agent.WithReActConfig(agent.ReActConfig{
	MaxIters:     10,
	StopOnReject: true,
})
```

## Context Configuration

`ContextConfig` defines compression thresholds, summary prompts, summary schema, and tool-result truncation:

```go
agent.WithContextConfig(agent.ContextConfig{
	TriggerRatio:    0.8,
	ReserveRatio:    0.1,
	MaxTokens:       32000,
	ToolResultLimit: 50000,
})
```

`MaxTokens` enables context pressure tracking and summary compression. When it
is `0`, the default strategy chain keeps the existing lightweight cleanup
behavior: offload base64 `DataBlock` values when an offloader is configured,
then truncate or offload oversized tool results.

When `MaxTokens` is positive, the default strategy chain includes
`ThresholdContextStrategy`. It records `AgentState.ContextStatus` and applies
three remaining-token thresholds:

| Threshold | Default | Behavior |
| --- | ---: | --- |
| Warning | `20000` | Record `warning` status |
| Compact | `13000` | Automatically summarize older context |
| Blocking | `3000` | Return `ContextWindowError` if compaction still leaves too little room |

The built-in absolute thresholds are applied only when `MaxTokens` is larger
than the warning threshold. For smaller model windows or tests, configure a
`ThresholdContextStrategy` explicitly with smaller thresholds.

The existing summary strategy remains available as a ratio-based fallback: when
the current request exceeds `TriggerRatio * MaxTokens`, it keeps the most recent
context, asks the model for a structured summary, and offloads compressed
messages through the configured offloader or workspace.

Customize the progressive thresholds by replacing the strategy chain:

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

Replace the context strategy chain when an application needs a custom store or compression policy:

```go
agent.WithContextStrategies(customStrategy)
```

Use `agent.DefaultContextConfig()` when you only need to override one field.

## Middleware

Register middleware with `agent.WithMiddlewares`. Middleware can intercept replies, reasoning, model calls, tool execution, and system-prompt construction.

The optional `middleware` package provides tracing middleware without making tracing part of the core `agent` package:

```go
agent.WithMiddlewares(middleware.NewTracingMiddleware(tracer))
```

Use `middleware/otel` only when OpenTelemetry integration is needed.
