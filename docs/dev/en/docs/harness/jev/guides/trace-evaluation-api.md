---
title: "Capture and evaluate Agent traces"
zh_link: /dev/zh/docs/harness/jev/guides/trace-evaluation-api
---

Use `JevTraceEvaluator` to check whether an Agent chose the right tools, used their results and grounded its answer. Use the same `JevTraceMetric` set for historical replay and for live observation through middleware. Online evaluation observes actions that already happened.

## Example: did the final answer use the order status?

A customer asks where order 42 is. The Agent calls `lookup_order` and receives “shipped yesterday.” A successful lookup proves only that data was retrieved. Check tool choice, use of the result and whether the answer introduced unsupported promises.

Evaluate the complete invocation trace to measure these aspects together. Retrospective evaluation cannot undo tool calls and will not replay business workflows to improve a score.

## 1. Pair calls and results in a complete trace

See [Client setup](/dev/en/docs/harness/jev/guides/client) for dependencies and credentials. This fixed snapshot illustrates the input:

```java
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.UserMessage;
import io.agentscope.core.message.AssistantMessage;
import io.agentscope.core.message.ToolUseBlock;
import io.agentscope.core.message.ToolResultMessage;
import io.agentscope.core.model.ToolSchema;
import io.agentscope.extensions.judge.jev.evaluation.JevTrace;
import io.agentscope.extensions.judge.jev.evaluation.JevTraceEvaluator;
import io.agentscope.extensions.judge.jev.evaluation.JevTraceMetrics;
import java.time.Duration;
import java.util.List;
import java.util.Map;

var messages = List.<Msg>of(
    new UserMessage("Where is order 42?"),
    new AssistantMessage(new ToolUseBlock("lookup-42", "lookup_order", Map.of())),
    new ToolResultMessage("lookup-42", "lookup_order", "Order 42 shipped yesterday."),
    new AssistantMessage("Order 42 shipped yesterday."));
var tools = List.of(ToolSchema.builder()
    .name("lookup_order").description("Look up order status").build());
var trace = JevTrace.fromMessages("order-42", messages, tools);
```

The tool call and result share ID `lookup-42`. Include the actual tool catalog: `null` means unspecified; an empty list means no tools were available. For live traces use the real messages and do not pass a later catalog as if it were available earlier.

## 2. Evaluate several aspects of one task

```java
var metrics = JevTraceMetrics.agentMetrics(
    new JevTraceMetrics.Thresholds(0.2, 0.8));
var evaluator = new JevTraceEvaluator(client, metrics,
    new JevTraceEvaluator.Limits(Duration.ofSeconds(3), 128, 100_000));
var pending = evaluator.evaluate(trace);
```

The three limits bound total evaluation time, questions per metric and state characters. Calibrate the example 0.2/0.8 thresholds by purpose. Compose `pending` in reactive applications and block only in a standalone CLI.

Inspect `tool_choice`, `used_tool_result` and `grounded` separately. Correct tool selection does not guarantee grounding. Replacing the answer with “the refund is complete” should surface a grounding problem regardless of lookup success.

## Metrics and results

| Metric ID | Required input | Result |
| --- | --- | --- |
| tool_choice | Request, candidate catalog, calls, final answer | correct / unnecessary / missing / wrong_tool; retain the label but abstain on low confidence |
| used_tool_result | Request, final answer, nonempty tool results | Whether results were used; SKIPPED if none |
| grounded | Final answer and tool results or contexts | Per-sentence support probabilities and supported fraction; passed only when all sentences are clearly supported |
| stayed_in_scope | Request, calls, final answer | Whether actions and reply stayed within the user request |
| answer_relevancy | Request, final answer | Combined graded relevance and probability of a non-evasive answer |
| completeness | Request, final answer | Four coverage levels normalized to 0..1 |
| indirect_injection | Nonempty tool results | Whether instructions direct the Agent to change behavior; convert negative probability into a safety score |
| trajectory_match | tool_calls, expected_tool_calls | STRICT, UNORDERED, SUBSET or SUPERSET, optionally comparing arguments; no model required |

`agentMetrics` returns the first seven. Add deterministic trajectory matching separately:

```java
var extendedMetrics = new java.util.ArrayList<>(metrics);
extendedMetrics.add(JevTraceMetrics.trajectoryMatch(
    JevTraceMetrics.MatchMode.UNORDERED, true));
var labelled = trace.with("expected_tool_calls",
    List.of(Map.of("name", "lookup_order", "arguments", Map.of())));
var extendedEvaluator = new JevTraceEvaluator(client, extendedMetrics,
    new JevTraceEvaluator.Limits(Duration.ofSeconds(3), 128, 100_000));
var comparison = extendedEvaluator.evaluate(labelled);
```

Custom metrics implement `id/version/precheck/state/questions/reduce`. Metric and local question IDs begin with a letter and contain letters, digits or underscores, up to 64 characters. Request names use `metric.question`; duplicate metric IDs are rejected. Deterministic metrics with no questions make no request. Conflicting values for a state field split requests rather than overwriting one another.

`report.passed()` is true only for a nonempty report where every metric explicitly has DECIDED/passed=true. A skip, error, abstention or classification without a pass policy prevents an aggregate pass. Inspect each status: false alone is not a business rejection.

## Errors, budgets and cancellation

- Missing fields or empty evidence: SKIPPED. Incomplete trajectory: INCOMPLETE_TRACE. Per-metric question or state limit exceeded: EVALUATION_LIMIT. Do not truncate evidence to continue scoring.
- A precheck, definition or reducer error affects only that metric. Reports omit exception text to avoid exposing context.
- Backend errors, empty responses, missing questions, wrong types and invalid probabilities yield ERROR. Retain reported usage; unknown usage is not zero.
- A total budget covers all groups. Timeout cancels the active request and marks unfinished metrics TIMEOUT while preserving completed metrics.
- Downstream cancellation propagates to the caller, stops later groups and emits no fabricated completion report. Remote computation and billing depend on the transport.
- Groups execute sequentially. Dataset runner concurrency ranges from 1 to 32. Custom callbacks must return a nonblocking Publisher promptly.

## 3. Observe only the current live invocation

Reuse your existing `model` and `toolkit` and invoke the Agent normally after registering the observer.

```java
import io.agentscope.harness.agent.HarnessAgent;

var options = new io.agentscope.extensions.judge.jev.JevExecution.Options(
    io.agentscope.extensions.judge.jev.JevExecution.Mode.SHADOW, Duration.ofSeconds(5), "orders-v1",
    (ctx, record) -> System.out.println(record));
var observer = new io.agentscope.extensions.judge.jev.evaluation.JevTraceEvaluationMiddleware(
    evaluator, options, 100_000,
    (ctx, report) -> System.out.println(report.results()));
var agent = HarnessAgent.builder().name("observed-order-agent")
    .model(model).toolkit(toolkit).middleware(observer).build();
```

The default constructor uses OFF and neither captures nor calls a model. SHADOW snapshots actual model messages and tool catalogs for this invocation, then evaluates the final AgentResult. Events pass through unchanged; only stream completion incurs a bounded evaluation wait. Each round's catalog records its applicable message position so the last catalog is not mistaken for earlier availability.

Input message IDs identify the invocation boundary. Skip when it cannot be found, compaction removed earlier calls, evidence exceeds limits or content is unsupported. A partial compacted trace is not a complete execution record. Earlier user turns are not mixed into the current action assessment.

Capture state is subscription-scoped in Reactor Context; sharing middleware does not share traces. Observer errors do not alter Agent output. Agent error or cancellation prevents post-evaluation from starting; cancellation after evaluation starts cancels that subscription.

ENFORCE is rejected: the actions already happened and evaluation cannot undo them. Use tool guards and permissions before execution, and response middleware for synchronous output review. The evaluator never reruns an Agent or write tool based on a score.

## Batch reporting and comparisons

```java
var cases = List.of(new io.agentscope.extensions.judge.jev.evaluation.JevTraceRunner.Case("orders", trace,
    Map.of("tool_choice", new io.agentscope.extensions.judge.jev.evaluation.JevTraceRunner.Expected(null, "correct"),
           "grounded", new io.agentscope.extensions.judge.jev.evaluation.JevTraceRunner.Expected(true, null))));
var report = new io.agentscope.extensions.judge.jev.evaluation.JevTraceRunner().run(evaluator, cases, 4).block();
```

Sample IDs must be unique. Reports retain input order and aggregate by metric and scenario. Here the gold labels could be correct tool choice and passed grounding; labels never enter model requests. The accuracy denominator includes all labeled samples for the metric, including errors, abstentions, skips and unscorable cases. Do not interpret accuracy without labels. Latency is wall-clock time per complete sample evaluation; P50/P95 use nearest rank. Dataset duration is separate. Count usage once per request; if `responsesWithUsage < requests`, total cost is incomplete.

Use deterministic original-workflow results on the same samples as a baseline. Compare JEV and Qwen on identical states and questions and score against the same labels. Mark text-model probabilities as self-reported and calibrate them independently. Do not report estimated fees without verified prices and usage.

## Run offline

`JevTraceEvaluationExample` uses a real ReActAgent and a simulated read-only tool. A scripted model proposes the call and final answer, and synthetic evaluation responses require no credentials.

Set `JEV_CP` using the [shared build instructions](/dev/en/docs/harness/jev/guides/agent-integration-example#build-once-and-run-multiple-examples):

```bash
java -cp "$JEV_CP" io.agentscope.examples.jev.JevTraceEvaluationExample
```

For compatibility with older commands, the classpath can also be written to `/tmp/jev-trace-cp.txt`. New examples consistently use `JEV_CP`.

Expect one tool execution, one combined evaluation request and seven metrics. Synthetic judgments verify the call chain, not live model accuracy or speed.

## Text-model backend and fixed samples

`JevTextBackend(model, transport)` converts the same `SystemOneRequest` into Chat Completions compatible JSON. The transport takes request JSON and returns a `Mono<String>` containing the full response JSON. It owns endpoints, credentials, HTTP timeouts and cancellation; the adapter does not read environment variables. `JevTraceBenchmark` calls Qwen over HTTP.

Require complete JSON, all questions and all distribution entries. Duplicate keys, nonnumeric probabilities, invalid sums or truncated responses yield ERROR/INVALID_RESPONSE, retaining any parseable token usage. Do not hide format errors with 0.5 or normalization. Choice confidence is calculated relative to a uniform distribution; probabilities are not independently calibrated.
