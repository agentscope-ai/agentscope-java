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

## Client behavior

- Calls `POST /v1/systemone`.
- Defaults to `https://api.typesafe.ai` and `jev-latest`.
- Reads `TYPESAFE_API_KEY` when no API key is set on the builder; `JEV_API_KEY` is still accepted as a fallback.
- Uses AgentScope's shared `HttpTransport`.
- Applies a 5-second per-attempt timeout by default; configure it with `timeout(Duration)`.
- Retries HTTP `429`, `529`, and `5xx` responses with exponential backoff.
- Validates that answer keys, answer types, probabilities, and score legends match the request.
- Throws `JevException` for non-retryable client errors, exhausted retries, and invalid responses.

## Suggest skills

`JevSkillSuggestionMiddleware` asks Jev to rank the visible skills and appends a short
`<skill_relevance>` block to the system prompt. It does not replace `DynamicSkillMiddleware` or
remove skills from the prompt.

```java
JevSkillSuggestionMiddleware skillSuggestion =
        JevSkillSuggestionMiddleware.builder(client)
                .repositories(skillRepositories)
                .skillFilter(skillFilter)
                .maxSuggestions(3)
                .confidenceThreshold(0.5)
                .failOpen(true)
                .build();

ReActAgent agent =
        ReActAgent.builder()
                .name("assistant")
                .model(model)
                .skillRepositories(skillRepositories)
                .middleware(skillSuggestion)
                .build();
```

Behavior:

- Runs once per agent invocation in `onAgent`.
- Appends the suggestion in `onSystemPrompt`.
- Accepts the same `SkillFilter` used by the agent, including runtime overlays.
- Uses a synthetic `__none__` option; skills must score above it to be suggested.
- Chunks rosters larger than 254 skills and reranks the chunk winners.
- Falls back to no suggestion when Jev fails and `failOpen(true)` is set.

## Select tools

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
- Calls Jev again when a newly activated skill changes the visible tool set.
- Chunks tool sets larger than 254 tools and reranks the chunk winners.
- Falls back to the original tool list when Jev fails and `failOpen(true)` is set.

## Not a ChatModel provider

This extension intentionally does not implement `Model`, `ChatModelBase`, or `ModelProvider`. Jev does not generate chat text or tool calls, so wiring it into `ModelRegistry` would misrepresent its capabilities. Use it from application code, middleware, routing logic, or a workflow decision node instead.
