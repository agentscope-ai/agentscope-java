---
title: Anthropic
zh_link: /v2/zh/integration/model/anthropic
---

`agentscope-extensions-model-anthropic` integrates Anthropic Claude models, including Anthropic-specific formatter and request DTO support.

## Add the dependency

```xml
<dependency>
    <groupId>io.agentscope</groupId>
    <artifactId>agentscope-extensions-model-anthropic</artifactId>
    <version>${agentscope.version}</version>
</dependency>
```

## ModelRegistry

Set `ANTHROPIC_API_KEY`, then use the `anthropic:<model>` id:

```java
ReActAgent agent = ReActAgent.builder()
    .name("assistant")
    .model("anthropic:claude-sonnet-4.5") // Resolved internally by ModelRegistry.resolve(modelId)
    .build();
```

## Explicit builder

Use the builder when you need a custom endpoint, formatter, transport, prompt caching, thinking, or generation options:

```java
import io.agentscope.core.model.GenerateOptions;
import io.agentscope.extensions.model.anthropic.AnthropicChatModel;
import java.util.Map;

AnthropicChatModel model = AnthropicChatModel.builder()
    .apiKey(System.getenv("ANTHROPIC_API_KEY"))
    .modelName("claude-sonnet-4-6")
    .stream(true)
    .defaultOptions(GenerateOptions.builder()
        .maxTokens(4096)
        .additionalBodyParam("thinking", Map.of("type", "adaptive"))
        .build())
    .build();
```

Adaptive thinking is the recommended mode for current Claude models that support it. For older
models that require manual extended thinking, set `thinkingBudget(2048)` instead; AgentScope maps it
to `thinking: {"type": "enabled", "budget_tokens": 2048}`. Manual thinking budgets are deprecated
on Claude 4.6 and rejected by Claude 4.7 and later. Do not combine `thinkingBudget` with an explicit
`thinking` additional body parameter. Consult the Anthropic model documentation before selecting a
mode because capabilities vary by model version.

### Bearer token authentication

For an Anthropic-compatible gateway that requires `Authorization: Bearer <token>`, set
`authToken` on the model builder:

```java
AnthropicChatModel model = AnthropicChatModel.builder()
    .baseUrl("https://gateway.example.com/anthropic")
    .authToken(System.getenv("ANTHROPIC_AUTH_TOKEN"))
    .modelName("claude-sonnet-4.5")
    .build();
```

Pass the token without the `Bearer ` prefix; the SDK adds it. `apiKey` sets `X-Api-Key`,
while `authToken` sets `Authorization`. Configure only one: setting both causes model
construction to fail with an `IllegalArgumentException`.
Configure authentication through the builder rather than `GenerateOptions.additionalHeaders`,
because the SDK owns these authentication headers.

## Spring Boot

Spring Boot applications can use the Anthropic starter:

```xml
<dependency>
    <groupId>io.agentscope</groupId>
    <artifactId>agentscope-anthropic-spring-boot-starter</artifactId>
    <version>${agentscope.version}</version>
</dependency>
```

To use a gateway with bearer token authentication:

```yaml
agentscope:
  model:
    provider: anthropic
  anthropic:
    base-url: https://gateway.example.com/anthropic
    auth-token: ${ANTHROPIC_AUTH_TOKEN}
    model-name: claude-sonnet-4.5
```

`agentscope.anthropic.auth-token` is optional. An unset or blank value leaves bearer
authentication disabled. Existing `agentscope.anthropic.api-key` configuration remains supported,
but configuring both nonblank credentials causes startup to fail. Builder customizers run before
validation and can clear a credential with `apiKey(null)` or `authToken(null)`.

Full builder options, formatters, credentials, and registry context details are covered in [Model](/v2/en/docs/building-blocks/model).
