# 权限

`permission` 包决定工具调用是否可以执行。

权限系统只处理工具执行前的决策，不替代业务鉴权、网络隔离或密钥管理。它的核心问题是：模型提出的这次工具调用是否允许执行，如果不允许，应该拒绝还是等待用户确认。

在 Agent 流程中，模型返回 `ToolCallBlock` 后，`agent.Agent` 会先调用权限引擎。权限通过才会执行工具；需要确认时，Agent 会发出确认事件并暂停。

```{mermaid}
flowchart TD
    Call["ToolCallBlock"] --> DenyRule{"命中 deny 规则？"}
    DenyRule -- 是 --> Deny["deny"]
    DenyRule -- 否 --> AskRule{"命中 ask 规则？"}
    AskRule -- 是 --> Ask["ask"]
    AskRule -- 否 --> ReadOnly{"模式允许<br/>当前只读调用？"}
    ReadOnly -- 是 --> Allow["allow"]
    ReadOnly -- 否 --> ToolCheck["工具 CheckPermissions"]
    ToolCheck --> ToolDecision{"工具返回决策"}
    ToolDecision -- allow --> Allow
    ToolDecision -- deny --> Deny
    ToolDecision -- ask --> Ask
    ToolDecision -- passthrough --> AllowRule{"命中 allow 规则？"}
    AllowRule -- 是 --> Allow
    AllowRule -- 否 --> Bypass{"bypass 模式？"}
    Bypass -- 是 --> Allow
    Bypass -- 否 --> Default["默认 ask<br/>dont_ask 转为 deny"]
```

## 模式

| 模式 | 行为 | 适合场景 |
| --- | --- | --- |
| `default` | 除非工具或规则允许，否则需要询问 | 交互式 Agent，默认推荐 |
| `accept_edits` | 允许只读工具，写入操作需要询问 | 允许模型探索上下文，但写入仍需确认 |
| `auto` | 由 `AutoPermissionClassifier` 判断原本需要询问的调用 | 后台任务、CI/CD 或无人值守流程 |
| `explore` | 允许只读工具，拒绝写入操作 | 只读分析、代码审阅、资料检索 |
| `bypass` | 通过工具自身检查后允许 | 完全受控环境，调用方已经做过授权 |
| `dont_ask` | 需要用户决策时直接拒绝 | 没有交互通道，宁可失败也不等待 |

模式不是安全边界本身。真正的边界来自工具实现、权限规则、Sandbox 沙箱范围、运行时隔离和业务授权。

## 引擎

```go
ctx := permission.NewContext(permission.ModeExplore)
engine := permission.NewEngine(ctx)

decision, err := engine.CheckPermission(context.Background(), readTool, input)
```

权限引擎按顺序检查拒绝规则、询问规则、模式行为、工具特定决策、允许规则和最终默认值。
在 `explore` 和 `accept_edits` 模式下，工具可以实现可选的
`permission.InputReadOnlyTool` 接口，让只读判定基于当前输入完成。例如
`Bash` 的静态 `IsReadOnly()` 是 `false`，但 `pwd`、`git status` 等当前命令会被
识别为只读调用并放行；写入类命令仍会被拒绝或进入后续权限检查。

## 决策顺序

一次权限判断会按以下顺序执行：

1. 匹配 deny 规则。命中后直接拒绝。
2. 匹配 ask 规则。命中后要求用户确认。
3. 处理 `explore`、`accept_edits` 和 `auto` 的只读快捷路径。
4. 调用工具自己的 `CheckPermissions`。
5. 匹配 allow 规则。
6. 如果是 `bypass` 模式，允许执行。
7. 其他情况返回默认 ask；`dont_ask` 模式会把默认 ask 转成 deny。

这个顺序让拒绝规则优先级最高。即使后面有 allow 规则，前面的 deny 仍会生效。

## 规则

```go
engine.AddRule(permission.Rule{
	ToolName:    "Write",
	RuleContent: "/tmp/agentscope/**",
	Behavior:    permission.BehaviorAllow,
	Source:      "example",
})
```

工具决定如何匹配 `RuleContent`。内置文件工具使用路径规则。函数工具默认匹配空规则，除非工具自身另有配置。

规则应尽量具体。允许一个目录通常比允许整个工具更可控；允许单个函数工具通常比允许所有自定义工具更清楚。

## Agent 中的确认流程

当工具需要确认时，`Agent` 会发出 `RequireUserConfirmEvent`。调用方展示工具名、输入和建议规则，让用户决定是否允许。

```go
var confirm *message.RequireUserConfirmEvent

err := runner.ReplyStream(ctx, user, func(event message.Event) error {
	if current, ok := event.(*message.RequireUserConfirmEvent); ok {
		confirm = current
	}
	return nil
})
if err != nil {
	panic(err)
}
```

用户允许后，把确认结果交回 Agent：

```go
result := message.NewUserConfirmResultEvent(confirm.ReplyID(), []message.ConfirmResult{{
	Confirmed: true,
	ToolCall:  confirm.ToolCalls[0],
	Rules:     confirm.ToolCalls[0].SuggestedRules,
}})

reply, err := runner.Reply(ctx, result)
```

如果用户拒绝，把 `Confirmed` 设为 `false`。Agent 会把拒绝结果写回当前回复上下文，让模型可以继续给出不执行工具的回答。

## Auto 权限

`ModeAuto` 面向无人值守 Agent 和 CI/CD 场景。它仍然让显式拒绝规则和询问规则保持最高优先级，直接放行只读调用，复用
`accept_edits` 模式下工具已有的安全检查；只有仍然需要人工确认的调用才交给 AI 分类器判断。分类器调用失败、返回空响应、返回非法 JSON 或非法行为时，权限引擎会 fail closed，返回 `deny`。

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

自定义分类器实现下面的接口即可：

```go
type AutoPermissionClassifier interface {
	Classify(context.Context, permission.ClassifierRequest) (*permission.Decision, error)
}
```

分类器会收到经过清洗的 transcript：包含用户文本和历史工具调用，以及当前工具动作。普通助手文本不会进入 transcript，避免模型刚生成的文本直接授权工具调用。分类器连续拒绝达到阈值后，auto 模式会退回 `ask`，让交互式调用方可以恢复。

## 工具如何参与权限

工具可以通过三个方法影响权限判断：

- `CheckPermissions`：根据输入返回 allow、deny、ask 或 passthrough。
- `MatchRule`：解释规则内容如何匹配当前输入。
- `GenerateSuggestions`：生成用户确认时可保存的建议规则。

函数工具默认需要确认。如果它确实只读，应显式设置：

```go
tool.WithFunctionReadOnly(true)
```

如果函数工具需要更细粒度权限，可以传入 `tool.WithFunctionPermissionFunc`。

## 取舍建议

- 默认交互式应用：使用 `ModeDefault`。
- 只读分析：使用 `ModeExplore`。
- 允许读取和安全编辑确认：使用 `ModeAcceptEdits`。
- 无人值守任务：使用 `ModeAuto`，并保留显式 deny 规则。
- 无交互服务：使用 `ModeDontAsk`，让不可确认操作直接失败。
- 已隔离且调用方完全授权：才考虑 `ModeBypass`。
