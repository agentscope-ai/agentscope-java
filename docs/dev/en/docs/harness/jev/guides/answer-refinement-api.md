---
title: "Review and revise an Agent's final draft"
zh_link: /dev/zh/docs/harness/jev/guides/answer-refinement-api
---

`JevResponseMiddleware` runs at `onModelCall`. It reviews final text answers for quality only when the model proposes no tools. Tool-call rounds keep their original events and are not rewritten as final answers; content safety checks still cover their text.

## Example: keep conditions on a refund

The policy says an unused order may be refunded within seven days, but the draft promises an unconditional refund. Retrieval has already finished. Running the whole Agent again could repeat business operations when only the answer needs correction.

Define a check that the answer preserves the conditions in the evidence. The middleware reviews the complete draft before publication. On failure it requests one text-only revision and reviews it again.

## 1. Define what the answer must satisfy

Initialize `client` as in [Client setup](/dev/en/docs/harness/jev/guides/client). The check uses `assistant_answer` and `supporting_context`, which contains successful tool results paired with their calls:

```java
import io.agentscope.extensions.judge.jev.JevJudge;
import io.agentscope.extensions.judge.jev.NoulQuestion;
import io.agentscope.extensions.judge.jev.JevExecution;
import io.agentscope.extensions.judge.jev.integration.JevResponseMiddleware;
import java.time.Duration;
import java.util.List;

var definition = new JevJudge.Definition("refund-answer-v1", List.of(
    new JevJudge.Criterion("grounded",
        new NoulQuestion(
            "Does assistant_answer retain all refund conditions in supporting_context, without inventing eligibility?",
            null),
        true, 0.2, 0.8)));
var middleware = JevResponseMiddleware.builder()
    .quality(new JevJudge(client, Duration.ofSeconds(2)), definition,
        new JevExecution.Options(JevExecution.Mode.SHADOW,
            Duration.ofSeconds(10), "refund-answer-v1",
            (context, record) -> System.out.println(record)))
    .maxRevisions(1)
    .build();
```

`true` means the condition should hold. The 0.2/0.8 thresholds distinguish failure, uncertainty and pass; calibrate them on business data. Each Judge call has two seconds; the ten-second budget covers quality review and all revisions. `maxRevisions(1)` prevents repeated rewrites.

SHADOW reviews the original without rewriting or blocking publication. After calibration, switch to ENFORCE to enable revision.

## 2. Register with your Agent

Use your application's existing `model` and `toolkit`:

```java
import io.agentscope.harness.agent.HarnessAgent;

var agent = HarnessAgent.builder()
    .name("refund-answer-review")
    .model(model)
    .toolkit(toolkit)
    .middleware(middleware)
    .build();
```

In ENFORCE, if the reviewer rejects “unconditional refund,” the middleware asks the current model for a new draft without tools. It publishes only if the revised draft passes. The intended revision restores “unused and within seven days”; actual wording and judgments depend on the models.

| Review or revision outcome | Application behavior |
|---|---|
| Original passes | Publish the original |
| Original fails, revision passes | Publish the revision |
| ERROR, INCONCLUSIVE or no revisions left | Throw `JevResponseMiddleware.Rejected`; do not publish the original |
| Revision is empty, unchanged or contains tool calls | Stop without publishing unapproved content |

Handle rejection with an application message or human review. Do not republish the rejected draft in the error handler. For custom rewrites, configure `.reviser(revision -> Flux<String>)`. Its only job is to edit text; it must not execute a refund or other write.

## Run the offline example

[JevIntegrationExample](/examples/jev/source/JevIntegrationExample.java.txt) implements this refund scenario. Set `JEV_CP` using the [build instructions](/dev/en/docs/harness/jev/guides/agent-integration-example#build-once-and-run-multiple-examples), then run:

```bash
java -cp "$JEV_CP" io.agentscope.examples.jev.JevIntegrationExample
```

The scripted example asserts that the final text retains `seven days`. The model runs three times: propose retrieval, write the original answer, then revise text with an empty tool list. This verifies integration, not live model accuracy or revision success rates.

## Evidence, budgets and output

Review state includes compatibility fields `prompt` / `answer`, plus `user_question`, `assistant_answer`, ID-paired `tool_calls`, `supporting_context` from successful tool results, and `trace_issues`. Applications can reuse `qualityState(input, answer)`. Metadata and reasoning are not evidence; verify the contents of tool results.

Incomplete trace pairing returns INCONCLUSIVE / INCOMPLETE_JUDGING_EVIDENCE. Serialized state over maxChars returns JUDGE_STATE_LIMIT. ENFORCE withholds publication; SHADOW leaves the output unchanged. See [Combining retrieval and review](/dev/en/docs/harness/jev/guides/evidence-pipeline-api). Define checks only on evidence actually present in the review state.

`maxRevisions` ranges from 0 to 10. The quality budget covers review and all revisions; roundBudget also includes original generation.

The default reviser calls the model from the current ModelCallInput with an empty tool list and rejects a revision that nevertheless contains tool calls. It does not rerun the Agent, tool loop or business writes. It reuses the original model parameters; structured output requiring a forced tool call cannot be revised at this layer.

On approval, the middleware replaces the text deltas; ReActAgent rebuilds the final message so streamed text matches AgentResult. It does not stream provisional revisions and preserves event pairing for the original call. The original ModelCallEnd usage excludes revision calls: use model-side telemetry to measure their cost separately.

OFF delegates completely. SHADOW reviews without revising or replacing. Buffers and counters belong to each subscription, so one middleware instance can serve multiple sessions.

To combine review with content checks, configure `.guardrails(...)` on the same builder. See [Content guardrails](/dev/en/docs/harness/jev/guides/content-guardrail-api) and the [combined example](/dev/en/docs/harness/jev/guides/agent-integration-example).
