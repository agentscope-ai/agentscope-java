---
title: OpenAI
zh_link: /dev/zh/integration/model/openai
---

`agentscope-extensions-model-openai` integrates OpenAI Chat Completions-style models. It is also the module to use for OpenAI-compatible endpoints such as DeepSeek, GLM, Kimi, MiniMax, and similar services when their wire format follows the OpenAI API.

> **Note:** This module calls `/v1/chat/completions`. For OpenAI models that require the Responses API for tool calling (such as `gpt-6.2-astra` and `gpt-6.2-sol`), use [OpenAI Official](/dev/en/integration/model/openai-official) (`openai-official`) instead. Also note that `temperature` and `top_p` are forwarded when set; omit them for models that disallow sampling parameters.

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

## Spring Boot

Spring Boot applications can use the OpenAI starter:

```xml
<dependency>
    <groupId>io.agentscope</groupId>
    <artifactId>agentscope-openai-spring-boot-starter</artifactId>
    <version>${agentscope.version}</version>
</dependency>
```

Full builder options, formatters, credentials, and registry context details are covered in [Model](/dev/en/docs/building-blocks/model).
