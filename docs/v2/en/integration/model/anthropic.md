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

## PDF documents and citations

PDF `DataBlock` content is sent as an Anthropic document block. Base64 data, local file paths or
`file://` URLs, and remote HTTP(S) URLs are supported. Local PDFs are encoded as base64 before the
request is sent.

```java
import io.agentscope.core.message.DataBlock;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.message.URLSource;
import io.agentscope.core.model.GenerateOptions;
import java.util.List;

DataBlock document = DataBlock.builder()
    .name("guide.pdf")
    .source(URLSource.builder()
        .url("file:///absolute/path/to/guide.pdf")
        .mimeType("application/pdf")
        .build())
    .build();

Msg request = Msg.builder()
    .role(MsgRole.USER)
    .content(document, TextBlock.builder().text("Summarize this document.").build())
    .build();

GenerateOptions options = GenerateOptions.builder()
    .citationsEnabled(true)
    .build();

model.stream(List.of(request), List.of(), options);
```

Anthropic requires citations to be enabled on all documents in a request or on none of them, so
`citationsEnabled` is request-scoped and is applied to every PDF document. Returned citations are
available from `TextBlock.getCitations()`. Cited responses may contain multiple text blocks because
each citation remains attached to the exact claim it supports. Request-level settings override
`defaultOptions`, and citations are disabled by default. If `onModelCall` middleware rewrites
the response text, citations are cleared because they no longer reliably identify the supported
claims; unchanged text keeps its citations. `Msg.getTextContent()` joins cited text fragments
without inserting newlines, preserving the original response text.

For streaming, citations are available in model `ChatResponse` text blocks and the final
`AgentResultEvent` message; individual `TextBlockDeltaEvent` events carry only text. The default
`AnthropicChatFormatter` preserves citations when messages are sent in subsequent turns.
`AnthropicMultiAgentFormatter` supports PDF input, but its merged conversation history is plain
text and does not retain citation metadata.

This integration currently supports PDF document blocks. Plain-text documents, custom-content
documents, and Anthropic Files API `file_id` sources are not yet supported.

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
