---
title: "Classify multiple business candidates"
zh_link: /dev/zh/docs/harness/jev/guides/application-api
---

`JevCandidateSelector` evaluates candidates independently when multiple ticket queues, documents or team members may apply. It returns selected, excluded and uncertain groups. The application decides what to do with them.

## Example: one ticket concerns both an order and a refund

A customer says, “My order has not shipped. Refund me if it cannot ship today.” Routing to just one of the orders, billing and technical queues may lose the refund request. Acting on the word “refund” alone would ignore the condition.

Use JEV to decide which queues should participate. It can recommend both orders and billing and exclude technical support. Classification directs handling; it cannot authorize a conditional refund.

## Initialize the components

```java
import io.agentscope.extensions.judge.jev.JevClient;
import io.agentscope.extensions.judge.jev.JevExecution;
import io.agentscope.extensions.judge.jev.JevJudge;
import io.agentscope.extensions.judge.jev.application.JevCandidateSelector;
import java.time.Duration;
import java.util.Map;

var client = JevClient.builder().build();
var options = new JevExecution.Options(
    JevExecution.Mode.SHADOW, Duration.ofSeconds(2), "ticket-routing-v1",
    (context, record) -> System.out.println(record));
var selector = new JevCandidateSelector(client, options, 0.2, 0.8);
var judge = new JevJudge(client, Duration.ofSeconds(2));
```

See [Client setup](/dev/en/docs/harness/jev/guides/client) for dependencies and credentials. Other application guides reuse the `selector` and `judge` initialized here; this example needs only `selector`.

The two thresholds mean clearly inapplicable and clearly applicable. A billing queue with probability 0.5 belongs in the uncertain group: clarify rather than treating it as unnecessary. The example thresholds are not calibrated.

## 2. Supply the request and candidate queues

```java
var pending = selector.select(ctx,
    Map.of("request", "My order has not shipped. Refund me if it cannot ship today."),
    "Does this request need the candidate support queue?",
    Map.of("orders", "Order inquiries",
           "billing", "Refunds and billing",
           "technical", "Technical issues"));
```

`ctx` is the current `RuntimeContext`. Each key is an application queue ID; its value describes the queue. Independent probabilities need not sum to 1. The host must already have checked authorization and availability.

## 3. Check decision status before using the recommendation

```java
var observed = pending.doOnNext(decision -> {
    if (decision.status() != JevExecution.Status.DECIDED) {
        System.out.println("Keep existing routing: " + decision.reason());
        return;
    }
    System.out.println("Recommended: " + decision.value().selected());
    System.out.println("Needs clarification: " + decision.value().uncertain());
});
```

Return or compose `observed` in reactive services; a standalone CLI may use `observed.block()`. SHADOW only records recommendations. Even in ENFORCE this API does not dispatch tickets: the host must explicitly adopt the recommendation.

## Run and verify

Set `JEV_CP` using the [example build instructions](/dev/en/docs/harness/jev/guides/agent-integration-example#build-once-and-run-multiple-examples), then run:

```bash
java -cp "$JEV_CP" io.agentscope.examples.jev.JevApplicationScenarios classification
```

With scripted probabilities of 0.95 for orders, 0.90 for billing and 0.05 for technical, the example selects `[orders, billing]`, excludes `[technical]` and performs zero business actions. This demonstrates separation of classification and execution, not live model accuracy. [JevApplicationScenarios source](/examples/jev/source/JevApplicationScenarios.java.txt)

## Errors and scale limits

Up to 1,024 candidates are processed in sequential batches of 64 under one budget. An empty list makes no request. Selected items sort by descending probability, then by ID. OFF or failure yields no usable selection: retain the original flow or escalate. Cancellation stops the subscription. The host owns queue mapping and retries.

To review the reply as well, see [Support requests and draft review](/dev/en/docs/harness/jev/guides/support-api).
