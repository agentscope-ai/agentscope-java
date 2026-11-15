---
title: "Reuse and extend evaluation definitions"
zh_link: /dev/zh/docs/harness/jev/guides/metric-definitions
---

When several Agents need the same quality standard, define it once as a versioned object and reuse it for online observation and offline evaluation. AgentScope provides two levels of definition.

## Example: apply one refund standard online and offline

A support Agent reviews replies online while an evaluator replays historical answers. If one checks unsupported refund promises and the other checks only tone, offline results cannot guide rollout.

Version conditions, input fields and thresholds together. Use the same definition for online Judge and offline Evaluator. For tool behavior reuse trace metrics; classifications need not become pass/fail.

## 1. Share a definition across draft review and sample replay

`JevJudge.Definition` is for draft reviews where all conditions must hold. The example shares field conventions among `JevJudge`, `JevEvaluator` and `JevResponseMiddleware`. `JevDraftPipeline` also uses Definition but its output state uses `request/evidence/draft`. Define matching criteria or normalize fields in your workflow rather than reusing mismatched field names.

```java
import io.agentscope.extensions.judge.jev.JevJudge;
import io.agentscope.extensions.judge.jev.NoulQuestion;
import io.agentscope.extensions.judge.jev.evaluation.JevEvaluator;
import io.agentscope.extensions.judge.jev.evaluation.JevTraceEvaluator;
import io.agentscope.extensions.judge.jev.evaluation.JevTraceMetrics;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

var definition = new JevJudge.Definition("refund-answer-v1", List.of(
    new JevJudge.Criterion("supported",
        new NoulQuestion("Is assistant_answer supported by supporting_context without inventing completed refunds?", null),
        true, 0.2, 0.8)));
var judge = new JevJudge(client, Duration.ofSeconds(2));
var evaluator = new JevEvaluator(judge, definition);
```

Keep field meanings consistent: `JevEvaluator` supplies `user_question`, `supporting_context` and `assistant_answer`. For direct Judge calls supply corresponding state. Update the version when questions, thresholds or pass policies change so reports remain distinguishable.

## 2. Reuse combinations of Agent behavior metrics

`JevTraceMetric` combines deterministic prechecks, question construction and result reduction. It supports Choice classification and purely deterministic checks without requiring every metric to produce pass/fail.

| Method | Implementation responsibility |
|---|---|
| `id()` / `version()` | Unique metric ID and definition version |
| `precheck(trace)` | Return SKIPPED for missing inputs, a deterministic result, or null to continue |
| `state(trace)` | Extract authorized material needed by this metric |
| `questions(trace)` | Map local question IDs to Question objects |
| `reduce(answers, trace)` | Return status, score, label, passed and any required evidence summary |

```java
var thresholds = new JevTraceMetrics.Thresholds(0.2, 0.8);
var metrics = new ArrayList<>(JevTraceMetrics.agentMetrics(thresholds));
metrics.add(JevTraceMetrics.trajectoryMatch(
    JevTraceMetrics.MatchMode.STRICT, true));
var traceEvaluator = new JevTraceEvaluator(client, metrics,
    new JevTraceEvaluator.Limits(Duration.ofSeconds(3), 128, 100_000));
```

Use the same traceEvaluator with `JevTraceRunner` for replay or `JevTraceEvaluationMiddleware` for live Agent observations. [Integration example](/dev/en/docs/harness/jev/guides/trace-evaluation-api)

Metric and local question IDs start with a letter, contain letters, digits or underscores, and are at most 64 characters. Metric IDs must be unique. The evaluator namespaces questions, merges compatible state and separates conflicting fields into different requests. Deterministic metrics without questions make no model call. Definition or reducer failures affect only their own metric.

## Keep statuses alongside scores

`JevMetricResult.passed` is nullable, and a non-DECIDED status cannot declare pass or fail. Preserve SKIPPED, INCONCLUSIVE and ERROR to distinguish missing inputs, uncertainty and failures; replacing them with zero loses the reason.

Threshold examples illustrate the API. Comparable evaluations require business labels, versions, execution modes and the full sample denominator. [Evaluation methodology](/dev/en/docs/harness/jev/evaluation)

## Run and verify

Set `JEV_CP` using the [example build instructions](/dev/en/docs/harness/jev/guides/agent-integration-example#build-once-and-run-multiple-examples), then run:

```bash
java -cp "$JEV_CP" io.agentscope.examples.jev.JevApplicationScenarios judge
java -cp "$JEV_CP" io.agentscope.examples.jev.JevApplicationScenarios evaluation
```

Both entry points reuse `answerDefinition()` from the example source: an individual review exercises status boundaries and a batch replays a positive and a negative case. Preset probabilities verify definition reuse; offline matches do not establish model accuracy. [Full source](/examples/jev/source/JevApplicationScenarios.java.txt)
