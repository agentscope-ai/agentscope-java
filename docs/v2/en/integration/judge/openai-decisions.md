---
title: OpenAI Decisions
description: Typed decisions over text and images using a dedicated judge extension.
zh_link: /v2/zh/integration/judge/openai-decisions
---

OpenAI Decisions evaluates shared evidence and returns a probability, a fixed choice, or a rubric score. The extension uses the official OpenAI Java SDK and lives under `agentscope-extensions-judge`, alongside JEV, and exposes `OpenAIDecisionsClient` independently of the chat model APIs. Applications can use its results to classify requests or choose their next action.

The [official guide](https://developers.openai.com/api/docs/guides/decisions) describes the API as public beta. The [API reference](https://developers.openai.com/api/reference/resources/decisions/methods/create) lists `gpt-6-luna`; this is the client's default model. Service access depends on your OpenAI account.

## Dependency and first decision

This addition targets `2.1.0-SNAPSHOT`; use a build containing this extension. With the AgentScope BOM, omit the dependency version.

```xml
<dependency>
    <groupId>io.agentscope</groupId>
    <artifactId>agentscope-extensions-openai-decisions</artifactId>
    <version>2.1.0-SNAPSHOT</version>
</dependency>
```

Set `OPENAI_API_KEY` before running. An explicit builder key takes precedence over the environment. Calling the following example sends the text and question to OpenAI and may incur charges.

```java
import io.agentscope.extensions.judge.openaidecisions.DecisionAnswer;
import io.agentscope.extensions.judge.openaidecisions.DecisionQuestion;
import io.agentscope.extensions.judge.openaidecisions.DecisionRequest;
import io.agentscope.extensions.judge.openaidecisions.DecisionResult;
import io.agentscope.extensions.judge.openaidecisions.OpenAIDecisionsClient;
import java.util.List;

OpenAIDecisionsClient client = OpenAIDecisionsClient.builder()
        .apiKey(System.getenv("OPENAI_API_KEY"))
        .build();
DecisionRequest request = DecisionRequest.builder()
        .input("I was charged twice for my order.")
        .question("department", new DecisionQuestion.Choice(
                "Which team should handle this?",
                List.of(new DecisionQuestion.Option("billing", "Payments and refunds"),
                        new DecisionQuestion.Option("technical", "Product issues"))))
        .build();
DecisionResult result = client.decideBlocking(request);
DecisionAnswer answer = result.answers().get("department");
if (answer instanceof DecisionAnswer.Choice choice) {
    System.out.println(choice.choice());
    System.out.println(choice.confidence());
    System.out.println(choice.probabilities());
} else if (answer instanceof DecisionAnswer.Refusal) {
    System.out.println("Send to manual review");
}
```

`DecisionRequest` contains 1–400 uniquely named questions. The optional request model overrides the client model. Inputs and question collections are copied when constructed, so a request can be reused safely. The optional `promptCacheKey()` partitions cache reuse; this extension does not expose `prompt_cache_options` or explicit cache breakpoints, so OpenAI uses its default implicit caching. `decide()` returns a cold `Mono`: each subscription sends a request. Use it in reactive applications; `decideBlocking()` is a convenience for non-reactive threads.

## Questions and answers

All public types are in `io.agentscope.extensions.judge.openaidecisions`.

| Question | Definition | Successful answer |
| --- | --- | --- |
| `DecisionQuestion.Predicate` | Instructions describing a condition | `DecisionAnswer.Predicate.probability()` in [0, 1] |
| `DecisionQuestion.Choice` | Instructions and 2–255 distinct `Option` values | `DecisionAnswer.Choice` with the selected value, confidence, and per-option probabilities |
| `DecisionQuestion.Score` | Instructions and a nonempty ordered list of `Level` values | `DecisionAnswer.Score` with the probability-weighted zero-based level index, confidence, and per-level probabilities |

Choice values may be strings or booleans; `"true"` and `true` are distinct. An option's description and a level's description are optional. Confidence is preserved separately from the selected probability. Do not interpret either as an application-specific guarantee of correctness; choose thresholds using labeled examples.

Every question may instead return `DecisionAnswer.Refusal`. Check for this type before taking an action. Other valid answers and usage remain available in the same `DecisionResult`. Refusals are not converted into zero probabilities or guessed choices.

The client matches answers by name and distribution entries by value/index, so response ordering is not significant. Missing, duplicate, unknown, or mismatched answers are rejected. Every choice or level must appear exactly once, numeric fields must be present and finite, probabilities and confidence must be in [0, 1], and probabilities must sum to one within `1e-6 × option count`. The chosen value must be one of the supplied options; the score must match the weighted indices within the same tolerance. Values are preserved without normalization. An invalid response fails the entire `Mono` with `OpenAIDecisionsException`.

`DecisionResult.usage()` contains input, output, and total token counts supplied by the provider. It also flattens the provider's token-detail fields into `cachedTokens`, `cacheWriteTokens`, and `reasoningTokens`. Missing usage is `null`; missing individual counts and detail fields remain `null`. A real zero remains zero. Usage on failed calls is not exposed.

## Images and mixed input

Pass image URLs to `DecisionInput.image(url)` for image-only input, or construct `DecisionInput(text, imageUrls)` to combine text and up to 128 images in one user message. `pngBytes` below represents PNG data loaded by your application.

```java
import io.agentscope.extensions.judge.openaidecisions.DecisionInput;
import java.util.Base64;

String dataUrl = "data:image/png;base64," + Base64.getEncoder().encodeToString(pngBytes);
DecisionRequest imageRequest = DecisionRequest.builder()
        .input(new DecisionInput("Inspect the product.", List.of(dataUrl)))
        .question("damaged", new DecisionQuestion.Predicate("Is there visible damage?"))
        .build();
client.decide(imageRequest).subscribe(imageResult -> System.out.println(imageResult.answers()));
```

The client accepts image data URLs and publicly accessible HTTP(S) image URLs, matching the API reference and official guide. The Java client sends these values to the provider without fetching the images locally; repository tests do not verify compatibility with a live service. This initial extension supports text and a single user message with text/image parts; conversation histories, tool-call inputs, and prompt-cache options are not exposed.

## Configuration and failures

The default API base is `https://api.openai.com/v1`. `baseUrl()` accepts a versioned API base, including any gateway path prefix, or a complete `/decisions` endpoint. Credentials, query strings, and fragments are rejected in the base URL. A custom base should retain the API version path.

The default timeout is 30 seconds and must be positive. Blocking SDK calls run on Reactor's `boundedElastic` scheduler. The timeout is also passed to the SDK. Timeout and cancellation interrupt the executing task; the SDK transport determines when an in-flight network call stops. Close clients created by the wrapper with `close()` or try-with-resources to release SDK connections. Spring closes the auto-configured client when its context shuts down.

HTTP failures expose a status code through `OpenAIDecisionsException.getStatusCode()`. HTTP 429, 5xx, and client timeout errors carry a retry hint; `isRetryable()` does not trigger a retry. SDK I/O failures and errors explicitly marked retryable by the SDK also retain a retry hint. The wrapper disables automatic retries on SDK clients it creates. `builder().client(openAIClient)` borrows an existing official SDK client, whose credentials, base URL, retry configuration, and lifecycle remain controlled by its owner. The wrapper still applies its model and request timeout. Provider bodies and raw SDK causes are omitted from client-generated exceptions.

## Spring Boot

Use the starter dependency instead of adding the extension directly, then inject `OpenAIDecisionsClient`.

```xml
<dependency>
    <groupId>io.agentscope</groupId>
    <artifactId>agentscope-openai-decisions-spring-boot-starter</artifactId>
    <version>2.1.0-SNAPSHOT</version>
</dependency>
```

```yaml
agentscope:
  openai-decisions:
    api-key: ${OPENAI_API_KEY}
    model: gpt-6-luna
    base-url: https://api.openai.com/v1
    timeout: 30s
```

The defaults above also apply to the starter. `agentscope.openai-decisions.enabled=false` disables client creation. A user-defined client bean takes precedence. Implement `OpenAIDecisionsClientBuilderCustomizer` to supply an official SDK client or override properties; Spring applies ordered customizers after binding properties.

A complete three-question example and image-input helper are available in `agentscope-examples/documentation/src/main/java/io/agentscope/examples/documentation2/judge/OpenAIDecisionsExample.java`.
