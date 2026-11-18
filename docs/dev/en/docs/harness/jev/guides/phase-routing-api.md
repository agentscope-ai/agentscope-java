---
title: "Route by capability, quota and explicit stage"
zh_link: /dev/zh/docs/harness/jev/guides/phase-routing-api
---

Configure routes and ordered model pools for multistage tasks. `JevPhaseRouting` selects within capacity, tool capability and quota limits. Applications can explicitly begin a stage or pin a model. A selection remains sticky within an Agent invocation; invalidation falls back to the original model.

## Example: plan a change, then execute known steps

An engineering assistant first assesses risk, then uses tools to execute agreed steps. Prefer `strong` for planning and `fast` for execution, while accounting for tool support, context capacity and exhausted quotas.

The host supplies current models and capabilities. Configuration specifies candidate order per route. An explicit stage chooses its configured route; only automatic mode asks JEV to choose among eligible routes.

## 1. Configure routes and observation mode

Set up `client` as in [Client setup](/dev/en/docs/harness/jev/guides/client). Start with SHADOW to observe recommendations without changing the actual model:

```java
import io.agentscope.extensions.judge.jev.JevExecution;
import io.agentscope.extensions.judge.jev.routing.JevPhaseRouting;
import io.agentscope.extensions.judge.jev.routing.JevRouteCatalog.*;
import io.agentscope.extensions.judge.jev.example.JevModelRouterMiddleware;
import io.agentscope.extensions.judge.jev.application.JevStageRouter;
import io.agentscope.harness.agent.HarnessAgent;
import io.agentscope.core.middleware.ModelCallInput;
import java.time.Duration;
import java.util.List;
import java.util.Set;

var options = new JevExecution.Options(
    JevExecution.Mode.SHADOW, Duration.ofSeconds(2), "routes-v1",
    (current, record) -> System.out.println(record));
var policy = new JevPhaseRouting(
    client::systemOne,
    List.of(
        new Route("plan", "Design and complex reasoning", List.of("strong", "fast")),
        new Route("execute", "Execute agreed steps", List.of("fast", "strong"))),
    Set.of("strong", "fast"), 0.8, true, options,
    (current, record) -> System.out.println(record));
var middleware = JevModelRouterMiddleware.builder(client)
    .phaseRouting(policy).build();
```

`plan` and `execute` are route IDs. The application sets model order and the allowlist. Calibrate the 0.8 selection threshold; `true` enables reasoning-effort selection. Separate observers capture recommendations and actual model calls so recommended models are not counted as dispatched.

## 2. Supply the invocation's model directory

The host provides `directory`, a `BiFunction<RuntimeContext, ModelCallInput, Mono<Snapshot>>` that reads capabilities, quota and token estimates for the current user and actual input. The host resolves model instances and credentials; JEV can choose only configured route IDs.

```java
var context = RuntimeContext.builder().userId("demo-user").sessionId("demo-session")
    .put(Source.class, new Source(directory))
    .build();
var agent = HarnessAgent.builder().name("phased-assistant")
    .model(originalModel).toolkit(toolkit).middleware(middleware).build();
```

The following is one possible directory candidate. `registeredModel` is built and authorized by the host. Supply real estimates for `estimatedInputTokens`, `outputReserve` and `consecutiveFailures` rather than using example constants in production.

The immutable Snapshot includes candidates, estimated input tokens, output reserve, optional minimumEffort and pinnedModel, and consecutive failures. Failure count is a host signal, not something inferred from words in tool output. Return authorized, available models for each user/session; untrusted session fields cannot serve as a directory. Model object identity must stay stable during an invocation; rebinding triggers fallback.

```java
var candidate = new Candidate(
    "strong", registeredModel, "Complex reasoning with tool support",
    64000L, 8000, true,
    Set.of(Effort.LOW, Effort.HIGH), Quota.AVAILABLE,
    null); // No pricing data: cost remains unknown.
var snapshot = new Snapshot(
    List.of(candidate), estimatedInputTokens, outputReserve,
    Effort.HIGH, null, consecutiveFailures);
```

Allow at most 128 candidates, 64 routes and 64 distinct model IDs per route. Availability is AVAILABLE / LOW / EXHAUSTED / UNKNOWN. Exclude EXHAUSTED; keep LOW and UNKNOWN as decision inputs. Check actual ModelCallInput.tools first; tool support must be explicitly declared.

Exclude candidates with unknown context capacity. Estimated input plus output reserve must fit the context. The output limit must accommodate the largest of reserve, maxTokens and maxCompletionTokens. Use host estimates and an explicit reserve. Currently only text and text tool results are routed; images or other non-text inputs keep the original model.

## Selection, reasoning effort and fallback

Choose using actual input at the first onModelCall; do not ask JEV again in the same Agent invocation. Before each dispatch reread the directory and check capacity, tools, quota, binding, pinning and minimum effort. After invalidation use the original model for the rest of the invocation, even if eligibility recovers during the same tool loop.

Selection priority:

1. If GenerateOptions specifies modelName, baseUrl, endpointPath or apiKey, preserve the entire original request so request-specific overrides cannot leak to another model.
2. Use an eligible Snapshot.pinnedModel without asking JEV.
3. For an explicit stage, use the first eligible model in its configured route without asking JEV.
4. In automatic mode, select only among eligible routes. The same request can choose effort; configuration still controls model order.

An explicit none, low confidence, invalid response, empty or failed directory, or exhausted budget falls back to the original model. No eligible candidate means no JEV call. Report separates route/model, applied effort, suggestedEffort, probability/confidence, exclusion reasons, judgment model and usage. Abstention is not “no business answer,” and low-confidence selection is not execution.

Efforts are NONE / MINIMAL / LOW / MEDIUM / HIGH / XHIGH / MAX / ULTRA. The host declares levels supported by the actual adapter. Preserve an explicit client reasoningEffort; an incompatible candidate cannot take over. Round a suggested level up to a declared level or cap at the deepest available, recording suggested and applied values separately. Never lower the deterministic minimumEffort. Apply a sole eligible level directly without another Choice. If a required route or effort decision is uncertain, keep the original model.

## Explicit stages

After confirming the mode, rebuild middleware with `options` set to ENFORCE to actually switch models. `messages` contains the current task messages:

```java
var stages = new JevStageRouter(middleware);
var phase = stages.begin(context, "plan", "Analyze the design and its risks").block();
agent.streamEvents(messages, phase.context());
```

Alternatively pass the phase and ModelCallInput to `stages.onModelCall(...)` with your downstream function. begin creates a fresh routing context; the first model call reads the directory. The goal argument exists for compatibility; decisions use user messages from the actual model input. Without phaseRouting, per-invocation model selection applies. Stages must name configured routes or fall back. Only the application initiates transitions; intermediate tool results cannot trigger them.

A new stage shares the host's business-context references and resets only routing scope. It neither copies business state nor replays prior stages. Normal Agent execution isolates route caches per subscription. Use an explicit Phase handle for one stage execution only; do not reuse it after cancellation.

## Budgets, observations and usage

The routing budget covers one model hook. Initial directory read, judgment and pre-dispatch recheck share the remaining budget; later calls perform only bounded directory rechecks. Generation time is excluded. Cancellation closes routing scope, cancels in-flight reads/judgments and stops future dispatch, without guaranteeing remote billing stops. Propagate generation failures normally; never reroute or replay the Agent or tools.

OFF delegates original input without directory reads or judgments. SHADOW records suggestions while preserving the actual ModelCallInput, model and options. Observer errors do not affect the Agent. JevExecution records phase-routing version, status, added latency and judgment usage. CallRecord records each dispatched model name, requested SDK reasoningEffort, completion/failure/cancellation, elapsed time and ChatUsage from ModelCallEndEvent.

CallRecord.dispatchedModel is the configured Model.getModelName; requestedEffort is what the adapter receives. Neither confirms the provider's model snapshot or final wire parameters. Calls bypassing this hook, such as quality middleware draft revisions, are not automatically included.

The host may provide Price(version,input,output,cached,creation) in USD per million tokens. Estimates treat SDK cached/cacheCreation tokens as subsets of input and price output separately. Missing prices or usage, invalid counts or overflow yield null cost. Retain the price version; unknown is not zero and synthetic prices are not real rates. Cross-session cache savings and quota probing are not inferred.

Applications can read the SDK Report explicitly. Do not use `policy.report(ctx)` for per-call attribution if concurrently reusing a RuntimeContext; use call observers and distinct runtime contexts.

## Runnable example

[JevPhaseRoutingExample](/examples/jev/source/JevPhaseRoutingExample.java.txt) runs automatic routing, explicit planning and explicit execution as separate invocations. Set `JEV_CP` using the [build instructions](/dev/en/docs/harness/jev/guides/agent-integration-example#build-once-and-run-multiple-examples), then run:

```bash
java -cp "$JEV_CP" io.agentscope.examples.jev.JevPhaseRoutingExample
```

No credentials required. A real ReActAgent runs auto, plan and execute, each with one read-only tool call and two model calls. Expect judgeRequests=1, strongCalls=4, fastCalls=2, originalCalls=0, toolCalls=3 and usageRecords=6. Both generation and judgments are scripted; the example verifies that routing matches dispatch.
