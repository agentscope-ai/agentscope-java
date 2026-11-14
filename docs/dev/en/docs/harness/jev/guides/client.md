---
title: "Create a client and request typed decisions"
zh_link: /dev/zh/docs/harness/jev/guides/client
---

Use `JevClient` for structured decisions returning probabilities, choices or scores. The generation model you configure still writes the user's answer.

## Add the dependency

```xml
<dependency>
  <groupId>io.agentscope</groupId>
  <artifactId>agentscope-extensions-jev</artifactId>
  <version>${agentscope.version}</version>
</dependency>
```

Harness middleware, compaction and Agent examples also require a matching `agentscope-harness` version. The extension's Harness dependency is optional.

## Example: is the claim “refund completed” supported?

The business record says no refund was initiated, but the Agent claims it finished. Put the answer and record in one state and ask whether the latter supports the former. The client sends and validates a typed request. A higher-level component decides whether to publish, revise or escalate.

## Request a decision and read the probability

```java
import io.agentscope.extensions.judge.jev.*;
import java.time.Duration;
import java.util.Map;

var client = JevClient.builder()
    .apiKey(System.getenv("TYPESAFE_API_KEY"))
    .model("jev-latest")
    .timeout(Duration.ofSeconds(5))
    .retryPolicy(new JevRetryPolicy(2, Duration.ofMillis(500)))
    .build();
var request = SystemOneRequest.builder()
    .state(Map.of("answer", "Refund completed", "record", "No refund initiated"))
    .question("supported", new NoulQuestion(
        "Does the business record support the answer?", null))
    .build();
var pending = client.systemOne(request); // Mono<SystemOneResult>
var result = pending.block();            // Blocking is appropriate in a standalone CLI.
var answer = (NoulAnswer) result.answers().get("supported");
System.out.println("Probability the record supports the answer: " + answer.noul());
```

The returned number is not refund status and does not change business records. For PASS/FAIL/INCONCLUSIVE and threshold handling, use [conditional reviews](/dev/en/docs/harness/jev/guides/judge-api) rather than reimplementing the branches.

In reactive services return or compose the `Mono`; do not subscribe twice just to log the result. Keep shared state unchanged until completion. Add multiple `.question(...)` calls with distinct IDs to the same builder for multiple judgments.

## Configuration and errors

The default endpoint is `https://api.typesafe.ai`, model `jev-latest`, and per-request timeout five seconds. Without an explicit key the client tries `TYPESAFE_API_KEY`, then `JEV_API_KEY`. Setting a key does not enable middleware.

The client posts to `/v1/systemone` and retries HTTP 429, 529 and 5xx with configured backoff. Nonretryable errors, exhausted retries and invalid responses throw `JevException`. Validation covers the question set, answer types, distributions and score grades. Never invent success for a missing answer.

An HTTP timeout differs from the total budget for a logical decision. [JevExecution](/dev/en/docs/harness/jev/guides/harness-runtime), the Judge or the component bounds batches, retries and backoff together. Cancellation stops the local subscription; transport behavior determines whether the remote request is interrupted.

## Spring Boot configuration

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
    model: jev-latest
    timeout: 5s
    retry:
      max-retries: 2
      initial-backoff: 500ms
```

Customize the builder with a `JevClientBuilderCustomizer` bean. The starter configures the client; register each Harness capability and select its mode explicitly.

## Compare a text model on the same questions

`JevTextBackend(model, transport)` converts a `SystemOneRequest` to a Chat Completions compatible request. Supply a `Function<String, Mono<String>>` transport that owns the endpoint, authentication, timeout and cancellation.

The adapter strictly validates JSON, questions and distributions. It does not invent missing answers or normalize invalid probabilities. Calibrate self-reported text-model probabilities separately. See the [evaluation guide](/dev/en/docs/harness/jev/evaluation) for runnable HTTP comparisons.

## Try the offline example first

The `judge` scenario in [JevApplicationScenarios](/examples/jev/source/JevApplicationScenarios.java.txt) demonstrates three outcomes for a similar refund evidence check. Set `JEV_CP` using the [build instructions](/dev/en/docs/harness/jev/guides/agent-integration-example#build-once-and-run-multiple-examples), then run:

```bash
java -cp "$JEV_CP" io.agentscope.examples.jev.JevApplicationScenarios judge
```

The example injects preset probabilities and makes no HTTP calls; it does not measure model quality. The client code above does call a real endpoint and needs credentials. Handle API failures as errors; never substitute offline results for a real decision.
