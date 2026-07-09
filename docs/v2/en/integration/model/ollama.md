# Ollama Model

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

## Spring Boot

There is currently no dedicated Ollama Spring Boot starter.

Full builder options, formatters, credentials, and registry context details are covered in [Model](../../docs/building-blocks/model.md).
