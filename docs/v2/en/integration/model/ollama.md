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

## Structured output

For `agent.call(messages, Output.class)` without application tools, the Ollama provider sends the output class's JSON Schema in the native `format` field. The returned message contains `_structured_output`, so `getStructuredData(Output.class)` reads the result while preserving the response text, thinking, and usage.

```java
public record ImageDescription(java.util.List<String> description, String name) {}

try (ReActAgent agent = ReActAgent.builder()
        .name("image-analysis")
        .model(model) // Use a local vision model for image inputs.
        .build()) {
    Msg result = agent.call(messages, ImageDescription.class).block();
    ImageDescription description = result.getStructuredData(ImageDescription.class);
}
```

The same format works for text and image inputs, in streaming and non-streaming mode. With application tools registered, structured output continues to use the synthetic `generate_response` tool. Do not set a JSON-only default `format` for that tool path. Native JSON Schema output requires a local Ollama server that supports `format`; Ollama Cloud does not currently support it.

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
