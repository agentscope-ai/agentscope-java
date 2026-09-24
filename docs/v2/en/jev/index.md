---
title: Jev
---

The `agentscope-extensions-jev` module, grouped under the `agentscope-extensions-judge` parent, provides a Java HTTP client for [TypeSafe System One](https://docs.typesafe.ai/) and Jev. Jev is not a chat model and is not registered as an AgentScope `Model` provider; use it when application code needs a fast, typed, calibrated decision such as routing, scoring, or classification.

## When to use

- You want to turn unstructured input into a typed `Noul`, `Choice`, or `Score` result.
- You need calibrated probabilities and confidence rather than generated prose.
- You want a cheap pre-check before invoking a larger reasoning model.

## Add the dependency

```xml
<dependency>
    <groupId>io.agentscope</groupId>
    <artifactId>agentscope-extensions-jev</artifactId>
    <version>${agentscope.version}</version>
</dependency>
```

## Quickstart

```java
import io.agentscope.extensions.judge.jev.ChoiceQuestion;
import io.agentscope.extensions.judge.jev.JevClient;
import io.agentscope.extensions.judge.jev.JevRetryPolicy;
import io.agentscope.extensions.judge.jev.SystemOneRequest;
import io.agentscope.extensions.judge.jev.SystemOneResult;
import io.agentscope.extensions.judge.jev.ChoiceAnswer;
import java.time.Duration;
import java.util.Map;

JevClient client =
        JevClient.builder()
                .apiKey(System.getenv("TYPESAFE_API_KEY"))
                .baseUrl("https://api.typesafe.ai")
                .model("jev-latest")
                .retryPolicy(new JevRetryPolicy(2, Duration.ofMillis(500)))
                .build();

SystemOneRequest request =
        SystemOneRequest.builder()
                .state("My payouts have been failing for 3 days.")
                .question(
                        "team",
                        new ChoiceQuestion(
                                "Which team should handle this?",
                                Map.of(
                                        "billing", "Payments and refunds",
                                        "technical", "Bugs and integrations")))
                .build();

SystemOneResult result = client.systemOneBlocking(request);

ChoiceAnswer answer = (ChoiceAnswer) result.answers().get("team");
if (answer.confidence() < 0.75) {
    // route to human review
} else {
    // route to answer.choice()
}
```

## Supported question types

| Type | Result |
| --- | --- |
| `NoulQuestion` | Probability that a yes/no statement is true |
| `ChoiceQuestion` | Selected option, every option's probability, and confidence |
| `ScoreQuestion` | Probability-weighted score, level legend, every level's probability, and confidence |

## Spring Boot starter

```xml
<dependency>
    <groupId>io.agentscope</groupId>
    <artifactId>agentscope-jev-spring-boot-starter</artifactId>
    <version>${agentscope.version}</version>
</dependency>
```

```yaml
agentscope:
  jev:
    api-key: ${TYPESAFE_API_KEY:}
    base-url: https://api.typesafe.ai
    model: jev-latest
    timeout: 5s
    retry:
      max-retries: 2
      initial-backoff: 500ms
```

`agentscope.jev.api-key` is optional. When it is not set, the client falls back to the
`TYPESAFE_API_KEY` and `JEV_API_KEY` environment variables.

For advanced configuration, define a `JevClientBuilderCustomizer` bean.

## Client behavior

- Calls `POST /v1/systemone`.
- Defaults to `https://api.typesafe.ai` and `jev-latest`.
- Reads `TYPESAFE_API_KEY` when no API key is set on the builder; `JEV_API_KEY` is still accepted as a fallback.
- Uses AgentScope's shared `HttpTransport`.
- Applies a 5-second per-attempt timeout by default; configure it with `timeout(Duration)`.
- Retries HTTP `429`, `529`, and `5xx` responses with exponential backoff.
- Validates that answer keys, answer types, probabilities, and score legends match the request.
- Throws `JevException` for non-retryable client errors, exhausted retries, and invalid responses.

## Example middlewares

The `io.agentscope.extensions.judge.jev.example` package ships three reference middlewares. They can
be attached directly with `ReActAgent.builder().middleware(...)`, or copied into a project and
tuned:

| Middleware | Interception point | Purpose |
| --- | --- | --- |
| `JevToolSelectionMiddleware` | `onReasoning` | Filter and rank tools sent to the primary model |
| `JevModelRouterMiddleware` | `onAgent` / `onModelCall` | Route between multiple models |
| `JevAutoModeMiddleware` | `onActing` | Assess risk before tool execution |

### Select tools

`JevToolSelectionMiddleware` reduces the tool schema list sent to the primary model. It preserves
core tools and ranks optional tools with Jev.

```java
JevToolSelectionMiddleware toolSelection =
        JevToolSelectionMiddleware.builder(client)
                .alwaysIncludeTools(Set.of("load_skill_through_path", "reset_tools"))
                .maxTools(3)
                .confidenceThreshold(0.5)
                .failOpen(true)
                .build();

ReActAgent agent =
        ReActAgent.builder()
                .name("assistant")
                .model(model)
                .toolkit(toolkit)
                .middleware(toolSelection)
                .build();
```

Behavior:

- Runs in `onReasoning`, before the model call.
- Preserves `load_skill_through_path`, `reset_tools`, and `generate_response` by default.
- Keeps optional tools whose probability is above the synthetic `__none__` option, up to `maxTools`.
- Re-runs on every reasoning step and sends the full `input.messages()` state to Jev.
- Chunks tool sets larger than 254 tools and reranks the chunk winners.
- Falls back to the original tool list when Jev fails and `failOpen(true)` is set.

### Route models

`JevModelRouterMiddleware` asks Jev to choose between configured models before the agent calls
them. Each candidate has routing criteria, and Jev returns a closed-set choice with calibrated
probabilities and confidence.

```java
JevModelRouterMiddleware modelRouter =
        JevModelRouterMiddleware.builder(client)
                .choice("fast", fastModel, "Direct lookups, extraction, and localized changes.")
                .choice("powerful", powerfulModel, "Architecture and high-stakes decisions.")
                .instructions("Choose the least costly model that can complete the task.")
                .confidenceThreshold(0.75)
                .failOpen(true)
                .build();

ReActAgent agent =
        ReActAgent.builder()
                .name("assistant")
                .model(fallbackModel)
                .middleware(modelRouter)
                .build();
```

Behavior:

- Reads the latest user message and makes one choice per agent invocation.
- Reuses that choice for model calls in the same invocation, so intermediate tool results cannot
  switch the model mid-run.
- Replaces only `ModelCallInput.model`; messages, tools, and generation options pass through.
- Stores the selected model, option probabilities, and confidence as
  `JevModelRouterMiddleware.decision(ctx)`.
- Falls back to the model configured on the agent when there is no user text, confidence is below
  the threshold, Jev fails while `failOpen(true)` is set, or the answer is unusable.
- Supports up to 255 model choices; larger candidate sets fail during configuration.

### Guard tool execution

`JevAutoModeMiddleware` decides, before a tool runs, whether a high-risk call is safe enough to
execute automatically.

```java
JevAutoModeMiddleware autoMode =
        JevAutoModeMiddleware.builder(client)
                .guardedTool("bash")
                .safetyThreshold(0.5)
                .failOpen(true)
                .build();

ReActAgent agent =
        ReActAgent.builder()
                .name("assistant")
                .model(model)
                .toolkit(toolkit)
                .middleware(autoMode)
                .build();
```

Behavior:

- Runs in `onActing`, before tool execution and ahead of the deterministic `PermissionEngine`
  pipeline.
- For calls whose name matches `guardedTools` and whose state is not `ALLOWED`, sends a yes/no
  risk question (`NoulQuestion`) to Jev. The request state includes the full conversation history
  and the tool input.
- Batches multiple guarded calls into a single Jev request, one question per call.
- `NoulAnswer.noul()` is P(safe); calls below `safetyThreshold` are denied.
- Denied calls never execute: a synthetic `DENIED` `ToolResultBlock` is written to the
  conversation state, and only safe calls are passed to the execution pipeline.
- Calls already confirmed as `ALLOWED` through HITL skip the Jev check, so human confirmation
  takes precedence.
- With `failOpen(true)`, a Jev failure lets the call through; with `failOpen(false)`, the failure
  propagates.
