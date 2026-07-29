# Loop Engineering

The `loop` directory provides framework APIs for building Loop Engineering agents. It does not replace the ReAct execution loop in the `agent` package. Instead, it adds goals, success criteria, budgets, verification, state, and events around an Agent run.

Loop Engineering turns repeated manual prompting into a reusable control loop: intent, context, action, observation, adjustment, and stop conditions. AgentScope Go provides these building blocks while scheduling, ticket systems, pull request policy, and auto-merge rules remain application responsibilities.

## Package Layout

Loop Engineering code is split by responsibility:

- `loop`: package overview only.
- `loop/core`: foundational loop contracts, including `Spec`, `Policy`, `Verifier`, `Observer`, and lifecycle event constants.
- `loop/runtime`: the Agent hook runtime behind `runtime.WithSpec`, including prompt injection, state counters, events, verification, and wrap-up behavior.
- `loop/automation/event`: platform-neutral event envelopes, event sources, event handlers, routers, and `TickerSource`.
- `loop/automation/runner`: maps routed events into Agent input and executes one loop-enabled Agent run.
- `loop/automation/store`: records events, runs, reports, findings, budget usage, and sinks.
- `loop/automation/gate`: pre-run gates, budget gates, and the generic scheduler.
- `loop/automation/goal`: cross-run goal continuation based on verifier results and `NextAction`.
- `loop/automation/verify`: `core.Verifier` backed by an independent checker Agent.
- `loop/automation/template`: reusable loop templates and skill reference declarations.
- `loop/automation/cloudevents`, `loop/automation/webhook`, and `loop/automation/queue`: external event adapters.

## When to Use It

Use `loop/core` and `loop/runtime` when an application needs to:

- constrain an Agent run with a clear goal and success criteria;
- track model calls, tool calls, token usage, and stop reasons;
- separate the implementer from the verifier;
- publish lifecycle events to UI, logs, audit sinks, or run logs;
- roll out from report-only to assisted or unattended operation.

For a regular multi-turn tool workflow, use `agent.Agent` and `agent.WithReActConfig` directly.

## Core Concepts

### Spec

`core.Spec` is the public contract for a loop design:

```go
spec := core.Spec{
	Name: "daily-triage",
	Goal: "scan repository signals and produce a report without modifying code",
	NonGoals: []string{
		"create pull requests",
		"merge code",
	},
	SuccessCriteria: []core.SuccessCriterion{
		{Name: "report", Description: "final reply lists findings and next action", Required: true},
	},
	Mode:   core.ModeReportOnly,
	Policy: core.DefaultPolicy(core.ModeReportOnly),
}
```

### Mode

`core.Mode` describes the autonomy level:

| Mode | Meaning |
| --- | --- |
| `core.ModeReportOnly` | Report and record state, but do not act autonomously. |
| `core.ModeAssisted` | Allow bounded action, usually with verifier or human review. |
| `core.ModeUnattended` | Allow unattended operation. Requires verifier, budget, and human gates. |

### Policy

`core.Policy` bounds one Agent run with iteration, model-call, tool-call, and token limits. `MaxAttempts` is part of the public policy contract for cross-run controllers, but the current runtime does not increment attempts by itself. When the run budget is exhausted, the runtime injects a wrap-up hint before the next reasoning pass and sets `tool_choice=none` on the model request.

### Verifier

`core.Verifier` supports maker/checker separation. The Agent that produced the work should not be the only judge of completion. A verifier can wrap tests, rules, another Agent, CI results, MCP tools, or business system checks.

```go
verifier := core.VerifierFunc(func(ctx context.Context, input core.VerificationInput) (core.VerificationResult, error) {
	return core.VerificationResult{
		Passed:   true,
		Reason:   "local checks passed",
		Evidence: []string{"go test ./..."},
	}, nil
})
```

## Agent Integration

Use `runtime.WithSpec` when constructing an Agent:

```go
agent, err := agent.NewAgent(
	"Friday",
	"You are concise.",
	chatModel,
	runtime.WithSpec(spec, runtime.WithVerifier(verifier)),
)
```

The runtime participates in these hooks:

- `SystemPromptMiddleware`: injects loop goals, non-goals, success criteria, and human gates.
- `ReplyMiddleware`: initializes `state.LoopContext`, records metrics, and emits lifecycle events.
- `ReasoningMiddleware`: emits iteration events and injects wrap-up hints when a budget is exhausted.
- `ModelCallMiddleware`: forces `tool_choice=none` during wrap-up.
- `ActingMiddleware`: preserves the tool execution chain without hiding business actions in middleware.

## State and Events

When `runtime.WithSpec` is enabled, the Agent state keeps `state.LoopContext`. It records loop name, goal, mode, iterations, model calls, tool calls, token usage, latest verifier result, and stop reason.

Loop events use existing `message.CustomEvent` values:

| Event | Meaning |
| --- | --- |
| `loop.start` | A loop run started. |
| `loop.iteration_start` | A reasoning iteration started. |
| `loop.iteration_end` | A reasoning iteration ended. |
| `loop.verify_start` | Verification started. |
| `loop.verify_end` | Verification finished. |
| `loop.wrap_up` | The loop entered wrap-up. |
| `loop.stop` | A loop run stopped. |

Existing SSE and AG-UI conversion middleware preserves these events as custom events.

## Boundaries

`runtime.Runtime` controls one Agent run. It does not include:

- cron, schedulers, or always-on workers;
- Git worktree creation and cleanup;
- GitHub PR creation, comments, or merges;
- Jira, Linear, Slack, DingTalk, or other business connectors;
- a fixed `STATE.md` schema;
- auto-merge or release policy.

Applications can compose those capabilities with `workspace`, `team`, `tool/mcp`, `tool/task`, independent adapters/examples, and business code.

## Event-Driven Automation

The `loop/automation` directory provides the orchestration layer above `core` and `runtime`. It keeps events generic so the framework does not depend on GitHub, Linear, CI, DingTalk, or any other platform model.

The core event envelope is `event.Event` from `github.com/yuluo-yx/agentscope-go/pkg/loop/automation/event`. It has CloudEvents-like fields such as `ID`, `Source`, `Type`, `Subject`, `Time`, `Data`, `CorrelationID`, `CausationID`, `DedupKey`, `Labels`, and `Priority`. Event types are open strings, for example `schedule.tick`, `manual.requested`, `webhook.received`, `ci.workflow.failed`, or adapter-defined values.

`runner.Runner` implements `event.EventHandler` and executes this flow:

1. Validate the generic event.
2. De-duplicate through `RunStore`.
3. Route the event with `Router`.
4. Optionally evaluate `Gate` as a generic hard stop before Agent execution.
5. Map it to a user message with `InputMapper`.
6. Resolve an Agent with `AgentResolver`.
7. Run `Agent.ReplyStream`.
8. Record a `RunRecord`.

The first built-in pieces are intentionally small:

- `event.TickerSource` emits `schedule.tick` events.
- `event.StaticRouter` and `event.RuleRouter` choose a loop and Agent.
- `runner.TemplateMapper` uses `text/template` to create Agent input.
- `runner.StaticAgentResolver` returns one configured Agent.
- `store.MemoryRunStore` records events and runs in memory.

The second orchestration layer includes:

- `goal.GoalRunner` continues the same goal based on verifier results and `NextAction`.
- `goal.ContinuePolicy` bounds cross-run attempts, wall-clock duration, and waiting states.
- `goal.TemplateNextActionMapper` turns failed verification into the next Agent input.
- `store.FileRunStore` persists events, runs, findings, and reports as JSONL and Markdown.
- `store.LoopReport` and `store.Finding` provide auditable human-readable outputs.

The production orchestration layer has started with lightweight contracts:

- `template.LoopTemplate` captures reusable loop configuration, input mapper text, and metadata.
- `template.SkillRef` declares project knowledge references without binding the framework to one plugin or skill file format.
- `runner.WorkspaceAllocator` allocates a workspace per run; `runner.Runner` injects workspace data into route metadata and records it on `store.RunRecord`.
- `runner.NoopWorkspaceAllocator` provides a default lease without creating external resources.
- `gate.Gate`, `gate.GateFunc`, `gate.GatePolicy`, and `gate.GateRule` enforce generic pre-Agent hard gates from event and route fields; blocked runs record `StopReason`, `GateReason`, and `GateMetadata`.
- `gate.Scheduler` and `gate.SchedulerPolicy` wrap any `event.EventHandler` with generic concurrency and backpressure controls, including maximum concurrent runs, maximum queued calls, per-`Source` limits, and per-`Type` limits. Full queues return `gate.ErrSchedulerQueueFull` so upstream code can retry, nack, or drop.
- `gate.AutomationBudget` and `gate.BudgetGate` summarize daily runs, token usage, and estimated cost from historical `store.RunRecord` values, with global, per-`Event.Type`, and per-loop-name limits. They return `waiting_user` before Agent execution when a budget is exceeded. `store.CostingRunStore` can call an application-provided `store.CostEstimator` before recording a run. Both `store.MemoryRunStore` and `store.FileRunStore` support `BudgetUsage`.
- `verify.AgentVerifier` implements `core.Verifier` with an independent checker Agent, expecting structured JSON by default while allowing applications to replace the mapper/parser.
- `store.Sink`, `store.SinkFunc`, and `store.MultiSink` publish `goal.GoalRunner` run/report outputs to application-defined targets.
- `store.FileSink` appends runs to `runs.jsonl` and writes `store.LoopReport` values as Markdown files, providing a platform-neutral local sink.
- `loop/automation/cloudevents` converts between CloudEvents SDK events and `event.Event`.
- `loop/automation/webhook` provides a generic HTTP webhook source; applications supply decoders and verifiers to convert any platform request into an `event.Event`.
- `loop/automation/queue` provides a generic queue source; applications supply a `Receiver` and `Decoder` for NATS, Kafka, Redis Streams, Cloud Pub/Sub, or other brokers while the source handles ack, nack, and serial backpressure.

Platform adapters, independent Git worktree adapters/examples, queue worker concurrency, GitHub/Linear/DingTalk platform sinks, a full plugin system, low-priority drop policy, model pricing adapters, and tool-level or platform-level gate rules belong to later production orchestration layers, not the root `loop/automation` package.

## Examples

- `example/loop/basic`: report-only loop with state and events.
- `example/loop/assisted-verifier`: assisted loop with verifier-based maker/checker separation.
- `example/loop/event-runner`: generic automation event routed to a loop-enabled Agent run.
- `example/loop/goal-runner`: verifier failure mapped through `NextAction` into a second run that then passes.
