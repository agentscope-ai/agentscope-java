---
title: "Evaluate batches of questions, evidence and answers"
zh_link: /dev/zh/docs/harness/jev/guides/evaluator-api
---

`JevEvaluator` applies a shared Judge definition to a question, evidence and answer. `JevEvaluationRunner` replays fixed cases and aggregates by scenario. Use them for regression checks after changing prompts, retrieval or generation models.

## Example: did a prompt change introduce unsupported refund promises?

Given “Has my refund gone through?” and a record showing no refund, one answer correctly asks to verify the status; another claims completion. Compare them against one explicit standard instead of judging each ad hoc.

Define the standard “the answer is supported by business evidence.” Put question, evidence and answer in `Evaluator.Request`. Supply the expected pass/fail label only to the runner, never the review model.

## 1. Reuse the draft review definition

```java
import io.agentscope.extensions.judge.jev.JevJudge;
import io.agentscope.extensions.judge.jev.NoulQuestion;
import io.agentscope.extensions.judge.jev.evaluation.Evaluator;
import io.agentscope.extensions.judge.jev.evaluation.JevEvaluator;
import io.agentscope.extensions.judge.jev.evaluation.JevEvaluationRunner;
import java.time.Duration;
import java.util.List;

var definition = new JevJudge.Definition("refund-answer-v1", List.of(
    new JevJudge.Criterion("supported",
        new NoulQuestion(
            "Is assistant_answer supported by supporting_context without inventing completed refunds?",
            null),
        true, 0.2, 0.8)));
var judge = new JevJudge(client, Duration.ofSeconds(2));
Evaluator evaluator = new JevEvaluator(judge, definition);
```

See [Client setup](/dev/en/docs/harness/jev/guides/client) for `client`. Use the same definition as for an [individual Judge review](/dev/en/docs/harness/jev/guides/judge-api); question, evidence and answer map to `user_question`, `supporting_context` and `assistant_answer`.

## 2. Construct a positive and a negative case

```java
var evidence = List.of("Business record: no refund has been executed");
var honest = new Evaluator.Request("honest", "Has my refund gone through?",
    evidence, "No refund yet; I need to verify the status.");
var invented = new Evaluator.Request("invented", "Has my refund gone through?",
    evidence, "The refund is complete.");
var cases = List.of(
    new JevEvaluationRunner.Case("Refund promises", honest, true),
    new JevEvaluationRunner.Case("Refund promises", invented, false));
var pending = new JevEvaluationRunner().run(evaluator, cases, 2);
```

`honest` and `invented` are unique IDs. “Refund promises” is the report group; the last boolean is the expected label. Concurrency 2 permits two reviews at once and preserves input order in the results. Never put the label for the incorrect answer in the evidence.

## 3. Read matches, abstentions and errors together

```java
var observed = pending.doOnNext(report -> {
    System.out.println("Overall: " + report.overall());
    System.out.println("Refunds: " + report.scenarios().get("Refund promises"));
    report.rows().forEach(row ->
        System.out.println(row.id() + " " + row.response().verdict().status()));
});
```

The report counts correct, incorrect, abstained and errored decisions separately. Accuracy divides correct decisions by all cases, including abstentions and API errors. An individual `pass()` is true only for an overall Judge PASS. `score()` is the fraction of conditions passed, not a replacement for the verdict.

A FAIL on the invented answer is a correct decision. A timeout with `pass=false` does not count as detecting the unsupported claim.

## Run and verify

Set `JEV_CP` using the [example build instructions](/dev/en/docs/harness/jev/guides/agent-integration-example#build-once-and-run-multiple-examples), then run:

```bash
java -cp "$JEV_CP" io.agentscope.examples.jev.JevApplicationScenarios evaluation
```

The offline fixture returns PASS and FAIL respectively, giving two matches. This verifies label isolation and counting, not 100% model accuracy. [Full example](/examples/jev/source/JevApplicationScenarios.java.txt)

To compare live backends, use identical samples, definitions and budgets for both evaluators. Keep each backend's errors, abstentions, usage and version. See [Evaluate your Harness](/dev/en/docs/harness/jev/evaluation).

## Budgets, reporting and cancellation

Use at most 10,000 cases, unique IDs and concurrency 1–32. Judge bounds each review; custom evaluators need their own timeout. The runner records item exceptions and empty responses as ERROR. Cancellation terminates the subscription without returning a normal completion report.

P50/P95 use nearest ranks and measure review time, not end-to-end Agent latency. Errors outside the adapter can have zero recorded duration; consider error counts as well. Reports omit raw questions, answers and evidence. Unknown usage stays unknown; do not estimate costs without verified prices.
