# Permission

The `permission` package decides whether a tool call can run.

## Modes

| Mode | Behavior |
| --- | --- |
| `default` | Ask unless a tool or rule allows the call |
| `accept_edits` | Allow read-only tools, ask for writes |
| `auto` | Let an `AutoPermissionClassifier` decide calls that would otherwise ask |
| `explore` | Allow read-only tools, deny writes |
| `bypass` | Allow after tool-specific checks |
| `dont_ask` | Deny when a user decision would be required |

## Engine

```go
ctx := permission.NewContext(permission.ModeExplore)
engine := permission.NewEngine(ctx)

decision, err := engine.CheckPermission(context.Background(), readTool, input)
```

The engine checks deny rules, ask rules, mode behavior, tool-specific decisions, allow rules, and final defaults.

## Auto Permission

`ModeAuto` is for unattended agents and CI/CD workflows. It keeps explicit deny
and ask rules authoritative, allows read-only calls directly, reuses the
`accept_edits` tool safety path, and only then calls an AI classifier for
remaining calls that would normally require human confirmation. Classifier
errors, empty responses, invalid JSON, and invalid behaviors fail closed as
`deny`.

```go
classifier, err := agent.NewModelAutoPermissionClassifier(chatModel)
if err != nil {
	panic(err)
}

state := agent.NewAgentState()
state.PermissionContext = permission.NewContext(permission.ModeAuto)

runner, err := agent.NewAgent(
	"runner",
	"Run the requested task.",
	chatModel,
	agent.WithAgentState(state),
	agent.WithAutoPermissionClassifier(classifier),
)
```

Custom classifiers can implement:

```go
type AutoPermissionClassifier interface {
	Classify(context.Context, permission.ClassifierRequest) (*permission.Decision, error)
}
```

The classifier receives a sanitized transcript containing user text and prior
tool calls, plus the current tool action. Assistant text is not included in the
transcript so model-generated prose cannot directly authorize a tool call.
After repeated classifier denials, auto mode falls back to an ask decision so an
interactive caller can recover.

## Rules

```go
engine.AddRule(permission.Rule{
	ToolName:    "Write",
	RuleContent: "/tmp/agentscope/**",
	Behavior:    permission.BehaviorAllow,
	Source:      "example",
})
```

Tools decide how to match `RuleContent`. Built-in file tools use path-oriented rules. Function tools match empty rules by default unless configured otherwise.
