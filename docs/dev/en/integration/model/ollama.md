---
title: Ollama
zh_link: /dev/zh/integration/model/ollama
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

## Thinking

For thinking-capable models, `GenerateOptions` maps to Ollama's `think` parameter:

| Option | Request |
| --- | --- |
| `reasoningEffort("low" / "medium" / "high")` | `think` set to that level (trimmed and case-insensitive; takes precedence over a budget) |
| `thinkingBudget(n)` with `n > 0` | `think` enabled |
| None set | `think` omitted |

```java
GenerateOptions options = GenerateOptions.builder()
    .reasoningEffort("high")
    .build();
```

Levels apply to models that support them (for example gpt-oss); toggle-based models use
`thinkingBudget`. Unsupported effort values fail fast with an `IllegalArgumentException`.
An `additionalBodyParams("think", ...)` entry overrides the mapping.

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

Full builder options, formatters, credentials, and registry context details are covered in [Model](/dev/en/docs/building-blocks/model).
