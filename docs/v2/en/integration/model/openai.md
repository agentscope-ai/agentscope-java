# OpenAI Model

`agentscope-extensions-model-openai` integrates OpenAI Chat Completions-style models. It is also the module to use for OpenAI-compatible endpoints such as DeepSeek, GLM, and similar services when their wire format follows the OpenAI API.

## Add the dependency

```xml
<dependency>
    <groupId>io.agentscope</groupId>
    <artifactId>agentscope-extensions-model-openai</artifactId>
    <version>${agentscope.version}</version>
</dependency>
```

## ModelRegistry

Set `OPENAI_API_KEY`, then use the `openai:<model>` id:

```java
ReActAgent agent = ReActAgent.builder()
    .name("assistant")
    .model("openai:gpt-4.1-mini") // Resolved internally by ModelRegistry.resolve(modelId)
    .build();
```

## Explicit builder

Use the builder when you need a custom endpoint, formatter, transport, or generation options:

```java
import io.agentscope.extensions.model.openai.OpenAIChatModel;

OpenAIChatModel model = OpenAIChatModel.builder()
    .apiKey(System.getenv("OPENAI_API_KEY"))
    .modelName("gpt-4.1-mini")
    .stream(true)
    .build();
```

For compatible endpoints, set `baseUrl(...)` and the model name expected by that service.

## Spring Boot

Spring Boot applications can use the OpenAI starter:

```xml
<dependency>
    <groupId>io.agentscope</groupId>
    <artifactId>agentscope-openai-spring-boot-starter</artifactId>
    <version>${agentscope.version}</version>
</dependency>
```

Full builder options, formatters, credentials, and registry context details are covered in [Model](../../docs/building-blocks/model.md).
