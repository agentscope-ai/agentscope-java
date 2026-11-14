---
title: "Review and revise drafts in a standalone application"
zh_link: /dev/zh/docs/harness/jev/guides/draft-pipeline-api
---

Use `JevDraftPipeline` when your application owns draft generation. It chains input checks, generation, complete-draft review and bounded revision. Only approved text appears in `publishedText` for the application to publish.

## Example: a reply in a support console

A customer asks whether an unshipped order qualifies for a refund. Policy says they may apply but receipt depends on actual processing. A generator might instead claim “the refund has arrived.” Before showing a sendable reply, check the draft and rewrite it once if needed, without executing a refund to correct the wording.

Use this pipeline when your application controls generation and publication. To intercept the Agent's final answer directly, use [response middleware](/dev/en/docs/harness/jev/guides/answer-refinement-api).

## 1. Define separate checks for input and draft

Initialize `judge` as in [Application component setup](/dev/en/docs/harness/jev/guides/application-api#initialize-the-components). The input check receives the original request. The output check receives `request`, `evidence` and `draft`; write questions about those fields:

```java
import io.agentscope.extensions.judge.jev.JevJudge;
import io.agentscope.extensions.judge.jev.NoulQuestion;
import io.agentscope.extensions.judge.jev.application.JevDraftPipeline;
import java.time.Duration;
import java.util.List;
import reactor.core.publisher.Flux;

var inputChecks = new JevJudge.Definition("refund-input-v1", List.of(
    new JevJudge.Criterion("in_scope",
        new NoulQuestion("Is this a customer request about orders or refunds?", null),
        true, 0.2, 0.8)));
var outputChecks = new JevJudge.Definition("refund-draft-v1", List.of(
    new JevJudge.Criterion("supported",
        new NoulQuestion(
            "Is draft supported by evidence without inventing completed refunds or arrival of funds?",
            null),
        true, 0.2, 0.8)));
var pipeline = new JevDraftPipeline(judge, 16000, 1, Duration.ofSeconds(15));
```

The example buffers up to 16,000 characters, allows one revision and gives input review, generation, output review and revision 15 seconds together. Generate only if input passes. Calibrate the example probability thresholds on business samples.

## 2. Connect generation and publish only approved text

Fixed strings make the inputs, mistaken draft and correction visible. Replace the two `Flux.just` calls with your own text-only generators. JEV performs the checks through the supplied `judge`.

```java
var outcome = pipeline.run(
    "Can I get a refund if the order has not shipped?",
    "Unshipped orders qualify for a refund request. Payment depends on actual processing.",
    inputChecks,
    outputChecks,
    () -> Flux.just("Your refund has arrived."),
    revision -> Flux.just("Unshipped orders qualify for a refund request. Payment depends on actual processing."))
    .block();

if (outcome.publishedText() != null) {
    System.out.println("Ready to publish: " + outcome.publishedText());
} else {
    System.out.println("Withheld: " + outcome.reason());
}
```

The incorrect draft should fail; publishable text returns only if the revision passes. A real model may judge differently, so inspect the outcome before sending. The reviser receives the current `draft`, complete `review` and `attempt` to target the failed conditions.

Neither generator nor reviser should rerun an Agent with write tools. Send a message only after successful completion. Do not stream intermediate generator fragments to the customer.

## 3. Handle nonpublishable outcomes

| `reason` | Meaning and action |
|---|---|
| `ACCEPTED` | `publishedText` is eligible for publication |
| `INPUT_NOT_ACCEPTED` | Input failed review; the generator is not invoked |
| `DRAFT_NOT_ACCEPTED` | Inconclusive/error review or revisions exhausted; notify the user or escalate |
| `UNCHANGED_DRAFT` | The revision is unchanged; stop retrying |
| `DRAFT_TOO_LARGE`, `TIMEOUT`, `PIPELINE_ERROR` | Withhold publication and follow the application's error policy |

Only FAIL can trigger revision; ERROR/INCONCLUSIVE cannot. On normal rejection, authorized reviewers may inspect `draftText`, but never publish it as a fallback. Error outcomes report zero `revisions`; do not infer prior call counts from it. Collect usage at the generator and client.

Cancellation propagates through active generation. The component does not stream unreviewed output. Show processing status if needed, then present the approved text in full.

## Run the offline example

The `draft` scenario in [JevApplicationScenarios](/examples/jev/source/JevApplicationScenarios.java.txt) supplies this request, evidence and both drafts. Set `JEV_CP` using the [build instructions](/dev/en/docs/harness/jev/guides/agent-integration-example#build-once-and-run-multiple-examples), then run:

```bash
java -cp "$JEV_CP" io.agentscope.examples.jev.JevApplicationScenarios draft
```

It checks three paths: one revision succeeds, an identical revision stops without publication, and rejected input skips generation. See `JevApplicationScenariosTest` in the examples module. Scripted generation and judgments do not measure live accuracy or latency.
