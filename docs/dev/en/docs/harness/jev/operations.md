---
title: "Budget decisions and consume telemetry"
zh_link: /dev/zh/docs/harness/jev/operations
---

JEV adds decision steps. Give them separate budgets and observe recommendations, actual execution and business outcomes independently to determine whether the Harness improves.

## Example: find the source of refund assistant latency

A request might select tools, guard a refund and review the answer. Added latency may come from generation, order APIs or JEV retries. Record version, budget and latency per judgment and correlate tool results from the same run instead of relying on total Agent time alone.

## Set a decision budget and register an observer

The example uses a bounded in-memory queue to show the observer boundary. In production an asynchronous consumer can drain it into your telemetry system.

```java
import io.agentscope.extensions.judge.jev.JevExecution;
import java.time.Duration;
import java.util.concurrent.ArrayBlockingQueue;

var decisions = new ArrayBlockingQueue<JevExecution.Record>(256);
var options = new JevExecution.Options(
    JevExecution.Mode.SHADOW, Duration.ofSeconds(2), "orders-v1",
    (ctx, record) -> decisions.offer(record));
```

When full, `offer` returns false and the example drops that observation without blocking the Agent. This is not reliable auditing. Track drops if needed and attach the current run/session before enqueueing. Each decision budget includes all its batches, retries and backoff, not the whole Agent. Response roundBudget includes original generation. Browser budgets include actions and verification. See each Source-taking overload for whether it also bounds reading.

Observers should return promptly. RuntimeExceptions are isolated, but synchronous blocking callbacks do not automatically receive a background thread or extra timeout. Bound HTTP, data-source and storage operations separately.

## Fields to record

| Field | Use |
|---|---|
| purpose, version, mode | Identify purpose, definition version and whether enforcement was active |
| status, reason | Distinguish valid decisions, uncertainty, skips, cancellation and errors |
| elapsed | Added latency of the decision step |
| recommendation | Tool name, model ID, action or advice for this purpose |
| RuntimeContext identity | Correlate run/session while isolating concurrent calls |

Do not count a SHADOW model recommendation as a dispatch, or a recommended denial as a prevented tool call. Stage-routing CallRecord records dispatch and model usage. Trace, code review and evidence reports record judgment requests in calls. Missing usage means unknown cost, not zero.

## Cancellation and failure

Cancellation propagates to active requests and data reads and stops later dispatch. It cannot necessarily undo remote requests or tool actions; use execution receipts to handle unknown outcomes.

Read failure behavior by purpose: selection and routing retain original input; failed protected-tool checks deny execution; failed pre-publication review withholds output. Retrospective trace evaluation and supervision record results without replaying the Agent.

## Move beyond shadow mode

Fix gold labels, mode and definition version, then record scenario-specific errors and abstentions. Select thresholds using the [evaluation guide](/dev/en/docs/harness/jev/evaluation). Before switching one purpose to ENFORCE, connect its data sources, permissions and failure handling. Continue measuring actual tool results and business quality separately.

## Verify fallback behavior by purpose

The [tool guard comparison](/examples/jev/tool-guard/README.md.txt) records both check latency and actual dispatch, with offline cases for timeout, cancellation, observer errors and concurrent isolation. Exercise these branches first, then measure quality and latency on business data. Offline simulation time is not an estimate of production capacity.
