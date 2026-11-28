---
title: OpenAI
zh_link: /dev/zh/integration/model/openai
---

`agentscope-extensions-model-openai` integrates OpenAI Chat Completions-style models. It is also the module to use for OpenAI-compatible endpoints such as DeepSeek, GLM, Kimi, MiniMax, and similar services when their wire format follows the OpenAI API.

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

Streaming completes after a `[DONE]` frame or when the HTTP body ends after every requested choice has a terminal finish reason. An earlier EOF is reported as a failed model call so a durable run can apply its recovery policy.

The managed OpenAI transport sends an HTTP/2 PING every 10 seconds to detect connections silently dropped by a gateway. It sends each POST once by default. The model also defaults to one attempt. A higher model attempt limit permits retries after HTTP 429 responses. Connection failures, timeouts, and 5xx responses are returned to the caller, where Durable can apply its own recovery policy and budget.

Customize the managed transport in Java with `OpenAIChatModel.httpTransportDefaults()` and pass the built configuration to `OpenAIChatModel.Builder.httpTransportConfig(...)`. Explicitly supplied transports and factory overrides use their own connection settings. Set a PING interval short enough to detect stale connections within your read budget. A zero interval disables PING.

## Spring Boot

Spring Boot applications can use the OpenAI starter:

```xml
<dependency>
    <groupId>io.agentscope</groupId>
    <artifactId>agentscope-openai-spring-boot-starter</artifactId>
    <version>${agentscope.version}</version>
</dependency>
```

| Spring Boot property | Default |
| --- | --- |
| `agentscope.openai.http2-ping-interval` | `10s` |
| `agentscope.openai.read-timeout` | `5m` |
| `agentscope.openai.retry-on-connection-failure` | `false` |
| `agentscope.openai.max-attempts` | `1` |

Enabling `retry-on-connection-failure` allows the transport to resend a POST. The model attempt limit applies only to model-level retries.

Full builder options, formatters, credentials, and registry context details are covered in [Model](/dev/en/docs/building-blocks/model).
