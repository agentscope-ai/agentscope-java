---
title: "Select relevant tools and limit optional candidates"
zh_link: /dev/zh/docs/harness/jev/guides/tool-selection-api
---

`JevToolSelectionMiddleware` evaluates which tools fit the current request before model reasoning, then limits optional tools. Several related tools can remain, or the middleware can explicitly offer no business tools. Selection grants no execution permissions.

## Example: one Agent for order lookup, conditional refunds and small talk

A support Agent registers `query_order`, `refund` and the structured response tool `generate_response`. Looking up A1001 generally requires only lookup. “Check it and refund it if unshipped” may require both lookup and refund. A greeting needs neither business tool.

Always picking one could omit order lookup; exposing everything adds irrelevant choices. JEV evaluates each tool independently before limiting the set, so it can preserve a group instead of forcing a single choice.

## 1. Configure optional and required tools

```java
import io.agentscope.extensions.judge.jev.JevExecution;
import io.agentscope.extensions.judge.jev.example.JevToolSelectionMiddleware;
import java.time.Duration;
import java.util.Set;

var options = new JevExecution.Options(
    JevExecution.Mode.SHADOW, Duration.ofSeconds(2), "order-tools-v1",
    (ctx, record) -> System.out.println(record));
var selector = JevToolSelectionMiddleware.builder(client)
    .execution(options)
    .maxTools(2)
    .confidenceThreshold(0.8)
    .rejectionThreshold(0.2)
    .alwaysIncludeTools(Set.of("generate_response"))
    .build();
```

Build `client` as in [Client setup](/dev/en/docs/harness/jev/guides/client). Configure the support workflow as follows:

| Setting | Effect |
|---|---|
| `maxTools(2)` | Keep up to two optional business tools so lookup and refund can coexist |
| `confidenceThreshold(0.8)` | Select a tool when relevance probability reaches this value |
| `rejectionThreshold(0.2)` | Reject only at or below this value; the middle interval is uncertain |
| `alwaysIncludeTools(...)` | Preserve the structured response tool if present; it does not count against the optional limit |
| `SHADOW` | Record recommendations while the model receives its original catalog |

Thresholds are illustrative. Default required tools also include `load_skill_through_path` and `reset_tools`. When configuring explicitly, preserve tools required by your Harness. This set retains only tools actually present; it cannot create or register tools.

## 2. Register with the existing Harness

```java
import io.agentscope.harness.agent.HarnessAgent;

var agent = HarnessAgent.builder()
    .name("order-support")
    .model(model)
    .toolkit(toolkit)
    .middleware(selector)
    .build();
```

Use your existing `model` and `toolkit`. Before each reasoning round the middleware reads actual messages and tool schemas, without requiring a separate user question. Selection limits what the model can propose; permissions and business rules still govern execution.

## 3. Handle an explicitly empty selection differently from failure

After validating observations, rebuild the middleware and Agent with ENFORCE.

| Scripted judgment | Tools supplied to the model in ENFORCE |
|---|---|
| Lookup 0.95, refund 0.05 | `query_order`, `generate_response` |
| Lookup 0.95, refund 0.90 | Lookup, refund and structured response |
| Both 0.05 | Only `generate_response` |
| Either tool 0.50, or request fails | Restore the original catalog; uncertainty does not mean no tools apply |

Validate real decisions on your own samples. Even if a conditional refund tool is retained, check its conditions in the [pre-execution guard](/dev/en/docs/harness/jev/guides/tool-guard-api) and business code. Selection alone cannot determine the right time to execute.

## Run and verify

Set `JEV_CP` using the [example build instructions](/dev/en/docs/harness/jev/guides/agent-integration-example#build-once-and-run-multiple-examples):

```bash
java -cp "$JEV_CP" io.agentscope.examples.jev.JevHarnessScenarios selection
```

The example covers lookup, conditional refunds, greetings, uncertainty and backend failure, and checks that SHADOW always preserves the catalog. Scripted probabilities exercise real middleware without model calls, so they do not compare model accuracy or latency. [Full source](/examples/jev/source/JevHarnessScenarios.java.txt)

## Candidate scale and cancellation

No optional tools or no user text means no request. Even one candidate is judged. Process candidates in sequential batches of 64 independent questions under one budget. Rank clearly applicable tools by probability for pruning, breaking ties by input order. The final tool list retains original ordering.

Missing or extra answers, invalid probabilities, timeout and backend errors restore the original catalog. Cancellation stops downstream reasoning. Thresholds must satisfy `0 <= rejection < confidence <= 1`.
