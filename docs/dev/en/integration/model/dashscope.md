---
title: DashScope
zh_link: /dev/zh/integration/model/dashscope
---

`agentscope-extensions-model-dashscope` integrates Alibaba Cloud DashScope Qwen models, including multimodal and reasoning-capable Qwen models.

## Add the dependency

```xml
<dependency>
    <groupId>io.agentscope</groupId>
    <artifactId>agentscope-extensions-model-dashscope</artifactId>
    <version>${agentscope.version}</version>
</dependency>
```

## ModelRegistry

Set `DASHSCOPE_API_KEY`, then use either `dashscope:<model>` or the Qwen short form:

```java
ReActAgent agent = ReActAgent.builder()
    .name("assistant")
    .model("dashscope:qwen-plus") // Resolved internally by ModelRegistry.resolve(modelId)
    .build();
```

```java
ReActAgent agent = ReActAgent.builder()
    .name("assistant")
    .model("qwen-plus") // Resolved internally by ModelRegistry.resolve(modelId)
    .build();
```

## Explicit builder

Use the builder when you need DashScope-specific options such as endpoint type, thinking, search, encryption:

```java
import io.agentscope.extensions.model.dashscope.DashScopeChatModel;

DashScopeChatModel model = DashScopeChatModel.builder()
    .apiKey(System.getenv("DASHSCOPE_API_KEY"))
    .modelName("qwen-plus")
    .stream(true)
    .build();
```

## Streaming responses

The DashScope SSE parser skips malformed JSON payloads and JSON `null` frames, so they do not replace valid responses that follow. Provider error responses still terminate the stream with `DashScopeHttpException` and retain the raw response body, including any request ID; transport failures remain errors rather than being treated as empty output. Skipping an invalid frame cannot recover content missing from that frame.

## Spring Boot

Spring Boot applications can use the DashScope starter:

```xml
<dependency>
    <groupId>io.agentscope</groupId>
    <artifactId>agentscope-dashscope-spring-boot-starter</artifactId>
    <version>${agentscope.version}</version>
</dependency>
```

Full builder options, formatters, credentials, and registry context details are covered in [Model](/dev/en/docs/building-blocks/model).
