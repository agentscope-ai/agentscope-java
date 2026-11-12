---
title: "Triage support requests and review replies"
zh_link: /dev/zh/docs/harness/jev/guides/support-api
---

`JevCustomerSupport` returns queue recommendations and a review of the draft reply. Use it for tickets with several requests, conditional refunds or potentially unsupported promises. The component returns a report; it does not submit tickets, send replies or issue refunds.

## Example: a conditional request becomes a false completion claim

A customer asks for a refund *if* an order cannot ship today. No refund has been initiated, but the Agent drafts “Your refund is complete.” Which queues should handle the request, and is that draft suitable to use?

JEV can recommend both orders and billing, then check whether the draft covers the condition and claims a completed action without a successful business record. Routing does not remove the customer's condition; a passing draft does not authorize payment.

## 1. Review the original request, unsent draft and business record

```java
import io.agentscope.extensions.judge.jev.JevExecution;
import io.agentscope.extensions.judge.jev.application.JevCustomerSupport;
import java.util.Map;

var support = new JevCustomerSupport(selector, judge);
var pending = support.review(ctx,
    "If the order cannot ship today, please request a refund.",
    "The refund is complete.",
    Map.of("refund", "Not executed"));
```

Initialize `selector` and `judge` as in [Application component setup](/dev/en/docs/harness/jev/guides/application-api#initialize-the-components); `ctx` belongs to this call. Use the unsent draft and actual execution records. The Agent's own statement is not a receipt.

## 2. Handle routing and draft quality separately

```java
var observed = pending.doOnNext(report -> {
    System.out.println("Triage status: " + report.triage().status());
    if (report.triage().status() == JevExecution.Status.DECIDED) {
        System.out.println("Recommended queues: " + report.triage().value().selected());
        System.out.println("Queues needing clarification: " + report.triage().value().uncertain());
    }
    System.out.println("Draft status: " + report.review().status());
    System.out.println("Criteria: " + report.review().findings());
});
```

Triage can select several of the fixed `orders`, `billing` and `technical` queues. Draft review returns a separate Judge status. Record suggestions, request human review or make a bounded revision. Each branch has its own budget; do not assume a combined deadline.

For other queue schemes, use [Candidate classification](/dev/en/docs/harness/jev/guides/application-api). To correct text, use [bounded revision](/dev/en/docs/harness/jev/guides/answer-refinement-api) rather than rerunning a refund workflow.

## Run and verify

Set `JEV_CP` using the [example build instructions](/dev/en/docs/harness/jev/guides/agent-integration-example#build-once-and-run-multiple-examples), then run:

```bash
java -cp "$JEV_CP" io.agentscope.examples.jev.JevApplicationScenarios support
```

The scripted result recommends orders and billing and returns FAIL for the draft. Refunds executed and messages sent both remain zero. This verifies report boundaries, not live accuracy. [JevApplicationScenarios source](/examples/jev/source/JevApplicationScenarios.java.txt)

## Where to integrate and how to handle failures

Invoke explicitly after generation and before sending. On triage errors or uncertainty, keep the existing assignment or request manual triage. Never treat draft ERROR/INCONCLUSIVE as a pass. Cancelling the combined subscription propagates to both review chains.

`JevCustomerSupport.definition()` checks requirement coverage and unsupported completion claims. Use [Judge definitions](/dev/en/docs/harness/jev/guides/judge-api) for other criteria.
