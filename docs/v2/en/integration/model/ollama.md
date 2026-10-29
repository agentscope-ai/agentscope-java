---
title: Ollama
zh_link: /v2/zh/integration/model/ollama
---

`agentscope-extensions-model-ollama` integrates locally hosted Ollama models. It is useful for local development, private deployments, and offline model serving.

## Add the dependency

```xml
<dependency>
    <groupId>io.agentscope</groupId>
    <artifactId>agentscope-extensions-model-ollama</artifactId>
    <version>${agentscope.version}</version>
</dependency>
```

## ModelRegistry

Use the `ollama:<model>` id. `OLLAMA_BASE_URL` is optional and defaults to the local Ollama endpoint when omitted.

```java
ReActAgent agent = ReActAgent.builder()
    .name("assistant")
    .model("ollama:llama3") // Resolved internally by ModelRegistry.resolve(modelId)
    .build();
```

## Explicit builder

Use the builder when you need a non-default Ollama endpoint, formatter, transport, proxy, or Ollama options:

```java
import io.agentscope.extensions.model.ollama.OllamaChatModel;

OllamaChatModel model = OllamaChatModel.builder()
    .modelName("llama3")
    .baseUrl("http://localhost:11434")
    .build();
```

## Per-call execution configuration

Timeout and retry for a single call are set through `GenerateOptions.executionConfig` and reach the Ollama HTTP call after the options conversion. Explicit fields override the model defaults; fields you leave unset fall back to the defaults (5-minute timeout, 3 attempts), and the configuration is applied client-side, never sent to the Ollama server:

```java
import io.agentscope.core.model.ExecutionConfig;
import io.agentscope.core.model.GenerateOptions;
import java.time.Duration;

model.stream(messages, /* tools = */ List.of(),
        GenerateOptions.builder()
                .executionConfig(ExecutionConfig.builder()
                        .timeout(Duration.ofSeconds(30))
                        .maxAttempts(2)
                        .build())
                .build());
```

Cancelling the subscription still propagates to the underlying HTTP call, and a request is not retried after user-visible content has already been emitted.

## Spring Boot

Spring Boot applications can use the Ollama starter:

```xml
<dependency>
    <groupId>io.agentscope</groupId>
    <artifactId>agentscope-ollama-spring-boot-starter</artifactId>
    <version>${agentscope.version}</version>
</dependency>
```

Configure the local Ollama model with `agentscope.model.provider=ollama`. The base URL
is optional and defaults to `http://localhost:11434`:

```yaml
agentscope:
  model:
    provider: ollama
  ollama:
    model-name: llama3
    # base-url: http://localhost:11434
```

Full builder options, formatters, credentials, and registry context details are covered in [Model](/v2/en/docs/building-blocks/model).
