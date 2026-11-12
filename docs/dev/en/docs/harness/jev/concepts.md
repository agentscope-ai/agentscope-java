---
title: "Question types, review outcomes and execution modes"
zh_link: /dev/zh/docs/harness/jev/concepts
---

Choose the result you need: a probability, one choice, or a graded score. Then decide how your application will use it. A well-formed result can still be wrong.

## Example: a conditional refund

A customer asks you to check an order and refund it only if it has not shipped. Both lookup and refund tools may be relevant, so ask a separate yes/no question about each. Use a single choice to pick one model for the analysis, or a graded score to assess the severity of a risk.

Even if JEV recommends a refund, verify the order facts and execution permissions. Distinguish the business input, the recommendation and what actually happened.

## Three question types

| Type | API | Result | Use case |
|---|---|---|---|
| Yes/no | `NoulQuestion` / `NoulAnswer` | Probability that a statement is true | Is this tool relevant? Is this answer supported? |
| Single choice | `ChoiceQuestion` / `ChoiceAnswer` | Choice, full distribution and confidence | Model routing or action selection |
| Graded score | `ScoreQuestion` / `ScoreAnswer` | Distribution across grades, weighted score and confidence | Risk severity or graded evaluation |

A `SystemOneRequest` contains shared `state` and questions keyed by ID. Put business evidence in the state and decision criteria in the questions. Keep gold labels and implicit authorization out of the input. When multiple candidates can apply, evaluate each independently instead of asking a mutually exclusive Choice question.

## Review outcomes and execution decisions

`JevJudge` aggregates Noul conditions into `PASS`, `FAIL`, `INCONCLUSIVE` or `ERROR`. Use it when every condition must hold. For an expected value of false, the probability of satisfying the condition is `1-p`. [Definitions and thresholds](/dev/en/docs/harness/jev/guides/judge-api)

`JevExecution.Decision` reports whether a component produced a usable recommendation: `DECIDED`, `INCONCLUSIVE`, `ERROR`, `CANCELLED` or `SKIPPED`. `DECIDED` alone says nothing about task success or content approval; read the component result. [Modes and budgets](/dev/en/docs/harness/jev/guides/harness-runtime)

`JevMetricResult` separates status, classification label, score and nullable `passed`. A classification metric without a pass policy need not produce PASS/FAIL. [Trace metrics](/dev/en/docs/harness/jev/guides/metric-definitions)

## Preserve uncertainty

Low confidence, an explicit rejection and a failed request are different outcomes. Tool selection may restore the original candidates, a guard may deny a protected call, and a retrospective evaluation may record an error or abstention. A missing decision is not permission to proceed.

Calibrate probabilities and confidence for your use case. A text model's self-reported probability is not measured accuracy. See [Evaluate your Harness](/dev/en/docs/harness/jev/evaluation).

## Start with one decision

Follow the [client example](/dev/en/docs/harness/jev/guides/client) to submit a reply claiming a refund is complete alongside a record showing none was initiated. Read the Noul probability, then add thresholds and outcome handling with [conditional reviews](/dev/en/docs/harness/jev/guides/judge-api). The runnable `JevApplicationScenarios judge` uses preset values to exercise PASS, FAIL and INCONCLUSIVE branches; it does not measure model accuracy.
