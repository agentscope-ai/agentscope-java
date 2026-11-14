---
title: "Define conditional review criteria"
zh_link: /dev/zh/docs/harness/jev/guides/judge-api
---

`JevJudge` aggregates required conditions into PASS, FAIL, INCONCLUSIVE or ERROR. Check whether a draft is supported or covers requirements, and reuse the versioned definition for online review and offline replay.

## Example: don't report a refund as complete when it hasn't started

A customer asks about a refund that the business record says never executed. A draft might correctly say verification is needed or falsely claim completion. Fluency alone cannot distinguish them.

Supply the customer question, business evidence and draft to Judge and ask whether evidence supports the answer. Judge returns a result before publication. The application decides whether to revise, escalate or publish.

## 1. Define the required conditions

```java
import io.agentscope.extensions.judge.jev.JevJudge;
import io.agentscope.extensions.judge.jev.NoulQuestion;
import java.time.Duration;
import java.util.List;
import java.util.Map;

var definition = new JevJudge.Definition("refund-answer-v1", List.of(
    new JevJudge.Criterion("supported",
        new NoulQuestion(
            "Is assistant_answer supported by supporting_context without inventing completed refunds?",
            null),
        true, 0.2, 0.8)));
```

`true` means the condition should hold. The 0.2 failure and 0.8 pass thresholds leave uncertainty between them. Calibrate these example thresholds on your own business samples.

`refund-answer-v1` identifies the standard. Update its version when questions, field semantics or thresholds change so rule changes can be distinguished from model changes.

## 2. Supply actual evidence and the draft

```java
var judge = new JevJudge(client, Duration.ofSeconds(2));
var state = Map.of(
    "user_question", "Has my refund gone through?",
    "supporting_context", List.of("Business record: no refund has been executed"),
    "assistant_answer", "The refund is complete.");
var pending = judge.judge(state, definition);
```

Build `client` as in [Client setup](/dev/en/docs/harness/jev/guides/client). The two-second review budget includes retries and backoff. Evidence must come from business records; the Agent's own promise cannot prove a refund succeeded.

These fields match [JevEvaluator](/dev/en/docs/harness/jev/guides/evaluator-api) input, so the same definition can be reused for batch regression.

## 3. Use the status to decide what happens to the draft

```java
var observed = pending.doOnNext(result -> {
    System.out.println("Definition version: " + result.definitionVersion());
    System.out.println("Review status: " + result.status());
    System.out.println("Condition findings: " + result.findings());
});
```

Return or compose `observed` in services; a CLI may use `observed.block()`. Do not subscribe again for logging: each subscription to the same Mono runs another review.

| Probability supporting the condition (example) | Status | Draft handling |
|---|---|---|
| 0.95 | PASS | Continue with application publication checks |
| 0.05 | FAIL | Revise the unsupported claim or send for review |
| 0.50 | INCONCLUSIVE | Obtain more evidence; do not treat as a pass |
| No valid response | ERROR | Follow the review failure policy; do not invent a probability |

A probability is not a textual explanation. `findings()` retains per-condition probabilities and statuses, without generating rationales, evidence citations or run/session IDs. Associate it with original business records yourself.

## Run and verify

Set `JEV_CP` using the [example build instructions](/dev/en/docs/harness/jev/guides/agent-integration-example#build-once-and-run-multiple-examples):

```bash
java -cp "$JEV_CP" io.agentscope.examples.jev.JevApplicationScenarios judge
```

The example supplies three preset probabilities for the same condition to exercise PASS, FAIL and INCONCLUSIVE without live calls. See [JevApplicationScenarios](/examples/jev/source/JevApplicationScenarios.java.txt).

For two combined conditions covering customer requirements and unsupported promises, run [JevCustomerSupportExample](/examples/jev/source/JevCustomerSupportExample.java.txt):

```bash
java -cp "$JEV_CP" io.agentscope.examples.jev.JevCustomerSupportExample
```

It runs offline unless you explicitly append `--live`. Offline outcomes do not measure live accuracy or speed.

## Multiple conditions, errors and cancellation

All conditions in a Definition are required. Any FAIL makes the aggregate FAIL; without a FAIL, uncertainty produces INCONCLUSIVE. Only all passes yield PASS. There is no weighted average or forced conversion of Choice/Score to pass/fail. Definitions must be nonempty and condition IDs unique.

For “does the draft contain an unsupported promise?”, set the expected value to false. Compare `1 - p` against the thresholds. Require `0 <= fail < pass <= 1`; the failure and pass ranges include their respective boundaries.

Budget exhaustion produces ERROR/TIMEOUT. Missing answers, wrong types or illegal probabilities detected by Judge produce ERROR/INVALID_RESPONSE. Client and other backend exceptions produce ERROR/BACKEND. Errors omit raw exception text and are not a probability of zero. Cancellation ends the subscription without manufacturing an ERROR; remote computation depends on the transport.
