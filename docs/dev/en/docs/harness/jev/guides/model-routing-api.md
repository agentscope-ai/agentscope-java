---
title: "Choose a model for one Agent invocation"
zh_link: /dev/zh/docs/harness/jev/guides/model-routing-api
---

`JevModelRouterMiddleware` chooses among available models registered by the application and keeps that choice within an Agent invocation. Route simple questions and complex analysis separately without replaying executed tools to select another model.

## Example: a short explanation versus analysis of unusual refunds

A user might ask for a one-sentence explanation of refund rules or for an investigation of multiple anomalous refunds with verification steps. A lightweight model may suit the first request and a stronger model the second. An investigation may span several tool and model calls; do not route anew each round.

Register allowed candidates and describe their roles. JEV chooses at invocation start. Later model requests recheck availability and compatibility with their tools.

## 1. Register candidates and capability constraints

```java
import io.agentscope.core.model.Model;
import io.agentscope.extensions.judge.jev.JevExecution;
import io.agentscope.extensions.judge.jev.example.JevModelRouterMiddleware;
import java.time.Duration;
import java.util.Set;

Set<Model> availableModels = Set.of(fastModel, strongModel);
Set<Model> toolCapableModels = Set.of(strongModel);
var options = new JevExecution.Options(
    JevExecution.Mode.SHADOW, Duration.ofSeconds(2), "support-models-v1",
    (ctx, record) -> System.out.println(record));
var router = JevModelRouterMiddleware.builder(client)
    .execution(options)
    .choice("fast", fastModel, "Simple questions and short explanations")
    .choice("strong", strongModel, "Complex analysis or requests with tools")
    .confidenceThreshold(0.8)
    .eligible((ctx, candidate) -> availableModels.contains(candidate.model()))
    .compatible((input, candidate) -> input.tools().isEmpty()
        || toolCapableModels.contains(candidate.model()))
    .build();
```

Build `client` as in [Client setup](/dev/en/docs/harness/jev/guides/client). `fastModel` and `strongModel` are configured Model instances. The example sets represent a host capability catalog, not claims about particular models.

| Configuration | Purpose |
|---|---|
| `choice` | Bound the model set and describe each model's use to JEV; unregistered models cannot be selected |
| `eligible` | Check authorization, quota or availability at selection and use |
| `compatible` | For requests with tools, admit only models the host knows support tools |
| `confidenceThreshold` | Use the original model on low confidence; calibrate the example threshold of 0.8 |

Both callbacks should return promptly. A real resource catalog must reflect changes, not rely indefinitely on a static set.

## 2. Keep the original model as fallback

```java
import io.agentscope.harness.agent.HarnessAgent;

var agent = HarnessAgent.builder()
    .name("support-analysis")
    .model(originalModel)
    .toolkit(toolkit)
    .middleware(router)
    .build();
```

`originalModel` is your existing model and must meet the original request's requirements. SHADOW records a recommendation but uses it unchanged. In ENFORCE only a clearly eligible recommendation replaces the model for this request.

## 3. Understand sticky selection and invalidation

One Agent invocation might make three model requests:

| Scenario | Models actually used across three rounds |
|---|---|
| A simple question selects fast | fast → fast → fast |
| Complex analysis selects strong | strong → strong → strong |
| strong becomes unavailable in round two and recovers in round three | strong → original → original |
| fast was selected but is incompatible with required tools | original → original → original |
| SHADOW recommends strong | original → original → original |

After invalidation the original model stays in use for the rest of the invocation. No reelection or tool replay occurs. Generation errors follow the original execution chain rather than causing the router to rerun the Agent.

## Run and verify

Set `JEV_CP` using the [example build instructions](/dev/en/docs/harness/jev/guides/agent-integration-example#build-once-and-run-multiple-examples):

```bash
java -cp "$JEV_CP" io.agentscope.examples.jev.JevHarnessScenarios routing
```

The example scripts Choice responses for all five cases and checks that each invocation judges only once. It verifies sticky routing without live generation and cannot compare model costs or task quality. [Full source](/examples/jev/source/JevHarnessScenarios.java.txt)

## Failures and stage transitions

Register 1–255 candidates. A noncandidate answer, low confidence, exception or timeout preserves the original model. Default compatibility accepts only tool-free requests; explicitly configure capability checks for tools. Routing state lives in the current RuntimeContext; do not reuse one context object across concurrent invocations.

To switch intentionally from planning to execution, establish a new boundary with [stage routing](/dev/en/docs/harness/jev/guides/phase-routing-api) rather than repeatedly changing recommendations in one invocation.
