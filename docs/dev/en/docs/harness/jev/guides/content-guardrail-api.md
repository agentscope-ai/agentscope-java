---
title: "Screen input and output content"
zh_link: /dev/zh/docs/harness/jev/guides/content-guardrail-api
---

`JevGuardrail` and `JevResponseMiddleware` screen input before model invocation and text before publication. Use them to distinguish allow, review, block and referral to human support. Content screening defaults to off and is configured separately from tool permissions.

## Example: screen a support answer before publication

A customer asks about refund conditions. Retrieved text might instruct the Agent to ignore rules or disclose internal information, and the answer may itself be unsuitable to publish. Screening the first user message alone misses later tool results and replies.

Use one content policy on both sides of generation. Input screening covers message text and tool results. Buffer output and decide whether to publish after screening. Refund authorization still belongs to the [tool guard](/dev/en/docs/harness/jev/guides/tool-guard-api) and the permission chain.

## 1. Configure checks and budgets

Build `client` as in [Client setup](/dev/en/docs/harness/jev/guides/client). Start in SHADOW to observe recommendations on representative samples:

```java
import io.agentscope.extensions.judge.jev.JevGuardrail;
import io.agentscope.extensions.judge.jev.JevExecution;
import io.agentscope.extensions.judge.jev.integration.JevResponseMiddleware;
import java.time.Duration;

var policy = JevGuardrail.defaults(client);
var options = new JevExecution.Options(JevExecution.Mode.SHADOW,
    Duration.ofSeconds(2), "content-v1",
    (context, record) -> System.out.println(record));
var middleware = JevResponseMiddleware.builder()
    .guardrails(policy, policy, options)
    .blockOnReview(false)
    .maxChars(64000).maxEvents(8192)
    .roundBudget(Duration.ofSeconds(120))
    .build();
```

The first two `guardrails` arguments are input and output policies; pass `null` to disable either. Two seconds bounds each semantic check; 120 seconds bounds the round including original model generation. Character and event caps bound memory used to buffer long answers.

SHADOW records recommendations and **does not block publication**. After validating the policy on business examples, rebuild in ENFORCE to block content. Start with the default policy or supply custom Hazard entries and thresholds.

## 2. Register with Harness and handle outcomes

Use your application's existing generation `model` and `toolkit`:

```java
import io.agentscope.harness.agent.HarnessAgent;

var agent = HarnessAgent.builder()
    .name("support-content-check")
    .model(model)
    .toolkit(toolkit)
    .middleware(middleware)
    .build();
```

Invoke the Agent normally. Publish text only from screened standard Agent events or the final result. Catch `JevResponseMiddleware.Rejected` and show an application message or human support option.

| Outcome | Behavior in ENFORCE |
|---|---|
| PASS | Continue generation or publication |
| REVIEW | Continue by default; stop with `blockOnReview(true)` |
| BLOCK | End the current model flow without publishing the rejected text |
| SUPPORT | Stop and let the application offer human support |

If output is BLOCK, even a complete draft is withheld from the protected output path. SUPPORT does not send a support ticket: inspect the recommendation in the observer and implement the handoff in your application.

## Run the offline example

[JevIntegrationExample](/examples/jev/source/JevIntegrationExample.java.txt) retrieves refund policy, screens input and output, revises a draft and publishes it. Set `JEV_CP` using the [build instructions](/dev/en/docs/harness/jev/guides/agent-integration-example#build-once-and-run-multiple-examples):

```bash
java -cp "$JEV_CP" io.agentscope.examples.jev.JevIntegrationExample
```

The scripted example verifies the integration, not live safety detection accuracy. For rejection, overflow, cancellation and session isolation behavior, see `JevResponseIntegrationTest` in the examples module.

## Errors and integration limits

The default policy checks for instruction overrides, serious harm or unlawful assistance, and self-harm crises. Severity can escalate REVIEW to BLOCK. Use `JevGuardrail.evaluate(SystemOneResult)` for offline policy checks. `screen` itself sets no timeout; middleware uses `JevExecution` to manage the budget and errors.

In ENFORCE a screening error or timeout also throws `Rejected`. Round text is withheld and proposed tools are not dispatched. SUPPORT recommendations remain in the observer; the application provides access to human support.

SHADOW preserves the original events and order without revision. Recommendations are recorded under `content`. At the capture limit it stops capturing without truncating original output. In ENFORCE overflow ends the flow. The round budget includes generation; a guard check budget includes JEV retries. Cancellation reaches the current subscription without reinvoking the Agent.

Only text is screened, not images, audio, tool arguments or reasoning blocks. Standard AgentEvent/AgentResult consumers are protected. Legacy raw model chunk hooks run before this middleware; do not publish through them or expose the unreviewed draft elsewhere.

Use the same `JevResponseMiddleware` for revision: screen safety before quality and screen each revision again. A quality retry cannot override a safety rejection.

See the [offline integration example](/dev/en/docs/harness/jev/guides/agent-integration-example).
