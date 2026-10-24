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

For `agent.call(messages, Output.class)`, the Ollama provider sends the output class's JSON Schema through Ollama's native `format` field. The returned message retains the JSON text and exposes the parsed value through `getStructuredData(Output.class)`.

```java
public record Weather(String city, double temperature) {}

try (ReActAgent agent = ReActAgent.builder()
        .name("weather")
        .model(model)
        .toolkit(toolkit)
        .build()) {
    Msg result = agent.call("What is the current temperature?", Weather.class).block();
    Weather weather = result.getStructuredData(Weather.class);
}
```

When application tools are available, native structured output uses two phases by default. Tool reasoning requests contain `tools` without `format`, so the model can call and chain tools normally. Once a reasoning round returns no tool call, the provider keeps that natural-language response as an internal draft and sends a final request with `format` and without `tools` or `tool_choice`. The final request can therefore organize the actual tool results into the requested schema without asking Ollama to choose between tool calling and JSON generation in the same request.

The terminal reasoning round makes two physical Ollama requests: one to decide whether more tools are needed, and one to produce the structured response. In streaming mode, the provider buffers the tool-phase response until it knows whether it contains a tool call. The internal draft is not emitted as user-visible text; only the final structured response is streamed. Earlier rounds that request tools continue through the normal ReAct loop.

You can use the synthetic `generate_response` tool path instead:

```java
OllamaChatModel model = OllamaChatModel.builder()
    .modelName("llama3")
    .nativeStructuredOutputWithTools(false) // Synthetic path when application tools are present
    .build();
```

Set `nativeStructuredOutput(false)` to use the synthetic path for all structured-output calls, including calls without application tools. The same two options are available through `ModelCreationContext` as `nativeStructuredOutput` and `nativeStructuredOutputWithTools`.

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
