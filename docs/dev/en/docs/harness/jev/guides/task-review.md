---
title: "Review a task artifact at a checkpoint"
zh_link: /dev/zh/docs/harness/jev/guides/task-review
---

`JevSupervisor` assesses completion and drift at a checkpoint chosen by your application. It uses host verification to recommend a next step. Use it when you already have task and artifact evidence and do not need continuous event observation.

## Example: the refund patch is written but tests have not run

An engineering Agent reports that it added an order-status check before refunds. The application must decide whether to keep implementing, run verification or begin delivery review.

JEV assesses whether artifact evidence supports completion; the host supplies actual verification status. Even high completion probability with `verificationSucceeded=false` can recommend only further verification, not completion review.

## 1. Provide the task and bounded artifact evidence

```java
import io.agentscope.extensions.judge.jev.application.JevSupervisor;
import java.util.Map;

var task = "Fix the refund eligibility check";
var evidence = Map.of(
    "changes", "Added an order-status check before refund",
    "testPlan", "Cover shipped and unshipped orders");
var supervisor = new JevSupervisor(judge);
var pending = supervisor.assess(task, evidence, false);
```

Initialize `judge` as in [Application component setup](/dev/en/docs/harness/jev/guides/application-api#initialize-the-components). The example uses short descriptions; supply authorized changes and check results in production, not just the worker's claim. The final false explicitly indicates no successful verification yet.

## 2. Act on the recommendation

```java
var observed = pending.doOnNext(report -> {
    System.out.println("Advice: " + report.advice());
    System.out.println("Assessment status: " + report.assessment().status());
});
```

| Advice | Next step for the patch |
|---|---|
| CONTINUE | Evidence does not support completion; keep implementing or obtain evidence |
| REQUEST_VERIFICATION | Implementation evidence supports completion; run the actual tests first |
| REVIEW_COMPLETION | Implementation evidence and successful host verification support review |
| MANUAL_REVIEW | Drift, uncertainty or a review error requires host review |

After tests finish, collect current artifact evidence again and call `assess(task, currentEvidence, true)`. The true flag must come from actual verification, not a model probability.

## Run and verify

Set `JEV_CP` using the [example build instructions](/dev/en/docs/harness/jev/guides/agent-integration-example#build-once-and-run-multiple-examples):

```bash
java -cp "$JEV_CP" io.agentscope.examples.jev.JevApplicationScenarios task-review
```

The example uses identical scripted completion and drift probabilities in both branches and changes only the simulated verification flag. It returns REQUEST_VERIFICATION then REVIEW_COMPLETION without running business tests or changing task state. [Full source](/examples/jev/source/JevApplicationScenarios.java.txt)

## Limits and continuous supervision

Built-in conditions are `complete` and `off_track` with 0.2/0.8 thresholds. Calling the API starts review; there is no OFF/SHADOW parameter, so skip the call when disabled. Timeout or failure recommends manual review without rerunning the task.

The boolean verification flag does not validate revision or scope. For long-running work, concurrent sessions or stale-evidence detection, use [continuous supervision](/dev/en/docs/harness/jev/guides/supervision-api) with a [trusted evidence source](/dev/en/docs/harness/jev/guides/supervision-evidence).
