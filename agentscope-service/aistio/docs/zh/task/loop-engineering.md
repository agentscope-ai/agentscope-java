# Loop Engineering

`loop` 目录提供构建 Loop Engineering 智能体的框架 API。它不会替代 `agent` 包的 ReAct 执行循环，而是在 Agent 外层增加目标、成功标准、预算、验证、状态和事件。

Loop Engineering 的核心思想是：应用不再只把一段提示词交给 Agent，而是把“目标、上下文、行动、观测、调整和停止条件”设计成可复用的循环。AgentScope Go 提供这些循环的 building blocks，调度器、工单系统、PR 策略和自动合并策略仍由应用侧决定。

## 包结构

Loop Engineering 相关代码按职责拆分：

- `loop`：只保留 package overview。
- `loop/core`：基础 loop 契约，包含 `Spec`、`Policy`、`Verifier`、`Observer` 和生命周期事件常量。
- `loop/runtime`：`runtime.WithSpec` 背后的 Agent hook runtime，负责系统提示词、状态计数、事件、验证和 wrap-up。
- `loop/automation/event`：平台无关事件信封、事件源、事件处理器、路由器和 `TickerSource`。
- `loop/automation/runner`：把事件路由结果映射成 Agent 输入并执行一次 loop-enabled Agent run。
- `loop/automation/store`：事件、run、报告、finding、预算用量和 sink 的记录能力。
- `loop/automation/gate`：执行前门禁、预算门禁和通用 scheduler。
- `loop/automation/goal`：基于 verifier 结果与 `NextAction` 的跨 run 目标续跑。
- `loop/automation/verify`：用独立 checker Agent 实现 `core.Verifier`。
- `loop/automation/template`：可复用 loop 配置模板和技能引用声明。
- `loop/automation/cloudevents`、`loop/automation/webhook`、`loop/automation/queue`：外部事件接入 adapter。

## 适用场景

`loop/core` 与 `loop/runtime` 适合以下场景：

- 需要把 Agent 运行过程按固定目标和成功标准约束起来。
- 需要记录每轮模型调用、工具调用、token 使用和停止原因。
- 需要把执行 Agent 和验证 Agent 或验证逻辑分开。
- 需要把 loop 生命周期事件推送到前端、日志、审计或运行记录。
- 需要先以 report-only 模式运行，再逐步升级到 assisted 或 unattended。

如果只需要一次普通多轮工具调用，直接使用 `agent.Agent` 和 `agent.WithReActConfig` 即可。

## 核心概念

### Spec

`core.Spec` 是循环设计的公开契约。它描述名称、目标、非目标、成功标准、范围、模式、预算和人工交接规则。

```go
spec := core.Spec{
	Name: "daily-triage",
	Goal: "扫描仓库信号并输出报告，不自动修改代码。",
	NonGoals: []string{
		"不创建 PR",
		"不自动合并代码",
	},
	SuccessCriteria: []core.SuccessCriterion{
		{Name: "report", Description: "输出发现项和下一步动作。", Required: true},
	},
	Mode:   core.ModeReportOnly,
	Policy: core.DefaultPolicy(core.ModeReportOnly),
}
```

### Mode

`core.Mode` 表示 loop 的自治等级：

| 模式 | 含义 |
| --- | --- |
| `core.ModeReportOnly` | 只报告和记录状态，不自动行动。 |
| `core.ModeAssisted` | 允许有限行动，建议配置 verifier 或人工确认。 |
| `core.ModeUnattended` | 允许无人值守运行，必须配置 verifier、预算和人工交接规则。 |

### Policy

`core.Policy` 限制一次 Agent run 的迭代、模型调用、工具调用和 token 预算。`MaxAttempts` 已经是 public policy contract 的一部分，用于后续跨 run 控制器；当前 runtime 不会自行递增 attempt。预算触顶后，loop runtime 会在下一次 reasoning 前注入 wrap-up hint，并在模型请求中设置 `tool_choice=none`。

### Verifier

`core.Verifier` 用于 maker/checker 分离。执行 Agent 不应该单独判断自己的工作是否完成。Verifier 可以封装测试命令、规则检查、另一个 Agent、CI 结果、MCP 工具或业务系统状态。

```go
verifier := core.VerifierFunc(func(ctx context.Context, input core.VerificationInput) (core.VerificationResult, error) {
	return core.VerificationResult{
		Passed:   true,
		Reason:   "local checks passed",
		Evidence: []string{"go test ./..."},
	}, nil
})
```

## 接入 Agent

使用 `runtime.WithSpec` 把 loop runtime 注册到 Agent：

```go
agent, err := agent.NewAgent(
	"Friday",
	"You are concise.",
	chatModel,
	runtime.WithSpec(spec, runtime.WithVerifier(verifier)),
)
```

`runtime.WithSpec` 会安装以下 Hook：

- `SystemPromptMiddleware`：注入 loop 目标、非目标、成功标准和人工交接规则。
- `ReplyMiddleware`：初始化 `state.LoopContext`，记录指标并发出生命周期事件。
- `ReasoningMiddleware`：发出 iteration 事件，并在预算触顶后注入 wrap-up hint。
- `ModelCallMiddleware`：预算触顶后强制 `tool_choice=none`。
- `ActingMiddleware`：保留工具执行链，不替换业务工具。

## 状态与事件

启用 `runtime.WithSpec` 后，Agent 状态中会维护 `state.LoopContext`。它记录 loop 名称、目标、模式、轮次、模型调用数、工具调用数、token、最新验证结果和停止原因。

事件通过现有 `message.CustomEvent` 输出：

| 事件 | 含义 |
| --- | --- |
| `loop.start` | 一次 loop run 开始。 |
| `loop.iteration_start` | 一轮 reasoning 开始。 |
| `loop.iteration_end` | 一轮 reasoning 结束。 |
| `loop.verify_start` | verifier 开始检查。 |
| `loop.verify_end` | verifier 返回结果。 |
| `loop.wrap_up` | loop 进入收束阶段。 |
| `loop.stop` | 一次 loop run 结束。 |

这些事件可以被现有 SSE 和 AG-UI 转换 middleware 保留为 custom event。

## 设计边界

`runtime.Runtime` 只控制一次 Agent run，不内置以下能力：

- cron、scheduler 或后台常驻服务。
- Git worktree 创建和清理。
- GitHub PR 创建、评论、合并。
- Jira、Linear、Slack、钉钉等业务连接器。
- 固定 `STATE.md` 文件格式。
- 自动 merge 或自动发布策略。

应用可以使用 `workspace`、`team`、`tool/mcp`、`tool/task`、独立 adapter/example 和业务代码组合这些能力。

## 事件驱动自动化

`loop/automation` 目录提供 `core` 与 `runtime` 之上的编排能力。它保持事件模型通用，避免框架核心依赖 GitHub、Linear、CI、钉钉或其他平台模型。

核心事件信封是 `github.com/yuluo-yx/agentscope-go/pkg/loop/automation/event` 下的 `event.Event`。它包含类似 CloudEvents 的字段，例如 `ID`、`Source`、`Type`、`Subject`、`Time`、`Data`、`CorrelationID`、`CausationID`、`DedupKey`、`Labels` 和 `Priority`。事件类型是开放字符串，例如 `schedule.tick`、`manual.requested`、`webhook.received`、`ci.workflow.failed`，也可以由 adapter 自行约定。

`runner.Runner` 实现 `event.EventHandler`，执行以下流程：

1. 校验通用事件。
2. 通过 `RunStore` 去重。
3. 通过 `Router` 路由事件。
4. 可选通过 `Gate` 做通用硬门禁；命中时记录 `RunRecord` 并停止。
5. 通过 `InputMapper` 映射为用户消息。
6. 通过 `AgentResolver` 选择 Agent。
7. 执行 `Agent.ReplyStream`。
8. 记录 `RunRecord`。

第一版内置能力保持克制：

- `event.TickerSource` 产生 `schedule.tick` 事件。
- `event.StaticRouter` 和 `event.RuleRouter` 选择 loop 和 Agent。
- `runner.TemplateMapper` 使用 `text/template` 创建 Agent 输入。
- `runner.StaticAgentResolver` 返回一个已配置 Agent。
- `store.MemoryRunStore` 在内存中记录事件和 run。

第二层编排能力包括：

- `goal.GoalRunner` 根据 verifier 结果和 `NextAction` 继续推进同一目标。
- `goal.ContinuePolicy` 限制跨 run 的最大 attempt、最长持续时间和等待状态。
- `goal.TemplateNextActionMapper` 把验证失败结果转换为下一轮 Agent 输入。
- `store.FileRunStore` 使用 JSONL 和 Markdown 持久化事件、run、finding 和报告。
- `store.LoopReport` 和 `store.Finding` 提供可审计的人类可读结果。

生产化编排层已经开始提供轻量契约：

- `template.LoopTemplate` 固化可复用的 loop 配置、输入映射模板和 metadata。
- `template.SkillRef` 声明 loop 需要的项目知识引用，但不绑定具体插件或 skill 文件格式。
- `runner.WorkspaceAllocator` 为每次 run 分配工作区，`runner.Runner` 会把 workspace 信息注入 route metadata 并写入 `store.RunRecord`。
- `runner.NoopWorkspaceAllocator` 提供不创建外部资源的默认工作区 lease。
- `gate.Gate`、`gate.GateFunc`、`gate.GatePolicy` 和 `gate.GateRule` 在 Agent 执行前基于通用事件和路由字段做硬门禁，命中时把 `StopReason`、`GateReason` 和 `GateMetadata` 写入 `store.RunRecord`。
- `gate.Scheduler` 和 `gate.SchedulerPolicy` 把任意 `event.EventHandler` 包装成受控 handler，支持最大并发、最大排队容量、按 `Source` 限流和按 `Type` 限流。队列满时返回 `gate.ErrSchedulerQueueFull`，由上游决定重试、nack 或丢弃。
- `gate.AutomationBudget` 和 `gate.BudgetGate` 基于历史 `store.RunRecord` 汇总每日 run 数、token 用量和估算成本，支持全局、按 `Event.Type` 和按 loop name 的预算。超预算时会在 Agent 执行前返回 `waiting_user`。`store.CostingRunStore` 可通过应用提供的 `store.CostEstimator` 在写入 run 前补充成本估算。`store.MemoryRunStore` 和 `store.FileRunStore` 都支持 `BudgetUsage`。
- `verify.AgentVerifier` 使用独立 checker Agent 实现 `core.Verifier`，默认要求 checker 输出结构化 JSON，也允许应用替换 mapper/parser。
- `store.Sink`、`store.SinkFunc` 和 `store.MultiSink` 把 `goal.GoalRunner` 生成的 run/report 发布到应用侧目标。
- `store.FileSink` 把 run 追加到 `runs.jsonl`，并把 `store.LoopReport` 写成 Markdown 文件，提供平台无关的本地 sink。
- `loop/automation/cloudevents` 提供 CloudEvents SDK 事件和 `event.Event` 的双向转换。
- `loop/automation/webhook` 提供通用 HTTP webhook source，应用通过 decoder 和 verifier 把任意平台请求转换为 `event.Event`。
- `loop/automation/queue` 提供通用队列 source，应用通过 `Receiver` 和 `Decoder` 接入 NATS、Kafka、Redis Stream、Cloud Pub/Sub 等 broker，并由 source 负责成功 ack、失败 nack 和串行背压。

平台 adapter、Git worktree 独立 adapter/example、队列并发 worker、GitHub/Linear/钉钉等平台 sink、完整插件系统、低优先级丢弃策略、模型价格表 adapter 和工具级/平台级 gate 规则属于后续生产化编排层，不进入 `loop/automation` 根包。

## 示例

- `example/loop/basic`：report-only loop，演示目标、成功标准、状态和事件。
- `example/loop/assisted-verifier`：assisted loop，演示 verifier 和 maker/checker 分离。
- `example/loop/event-runner`：把通用自动化事件路由到启用了 loop 的 Agent run。
- `example/loop/goal-runner`：演示 verifier 第一次失败后按 `NextAction` 自动续跑，并在第二次通过后停止。
