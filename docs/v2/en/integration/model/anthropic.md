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
import io.agentscope.extensions.model.anthropic.AnthropicChatModel;

AnthropicChatModel model = AnthropicChatModel.builder()
    .apiKey(System.getenv("ANTHROPIC_API_KEY"))
    .modelName("claude-sonnet-4.5")
    .stream(true)
    .build();
```

## Server-side tools

Anthropic built-in tools run on Anthropic's infrastructure instead of the local AgentScope toolkit.
Build a strongly typed SDK tool definition, then wrap the resulting `ToolUnion` with
`AnthropicServerTool.of(...)`:

```java
import com.anthropic.models.messages.ToolUnion;
import com.anthropic.models.messages.WebSearchTool20250305;
import io.agentscope.extensions.model.anthropic.tool.AnthropicServerTool;

AnthropicChatModel model = AnthropicChatModel.builder()
    .apiKey(System.getenv("ANTHROPIC_API_KEY"))
    .modelName("claude-sonnet-4.5")
    .addServerTool(AnthropicServerTool.of(
        ToolUnion.ofWebSearchTool20250305(WebSearchTool20250305.builder().build())))
    .build();
```

Server tools can be combined with local function tools. Anthropic selects tools by name, so a name
collision fails fast. `AnthropicServerTool.of(...)` accepts SDK built-in tools only, not custom
client tools.

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

## Client-side tool search

Register `ToolSearchMiddleware` on the agent to use a custom client-side search
strategy with native loading. The adapter sends the eligible tools with
`defer_loading: true`, while search and always-visible tools stay non-deferred.
Search results include `tool_reference` blocks, which Anthropic expands using the
full definitions in the request's `tools` array. Deferred definitions are still
sent on every request, but enter model context only when loaded. Prompt-cache
markers are placed on the last non-deferred client tool, never on deferred tools.
Use a model supporting this protocol, or select the ordinary-function fallback.
See [on-demand tool search](/v2/en/docs/building-blocks/tool#on-demand-tool-search).

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
