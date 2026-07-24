# Anthropic Model

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
each citation remains attached to the exact claim it supports.

This integration currently supports PDF document blocks. Plain-text documents, custom-content
documents, and Anthropic Files API `file_id` sources are not yet supported.

## Spring Boot

Spring Boot applications can use the Anthropic starter:

```xml
<dependency>
    <groupId>io.agentscope</groupId>
    <artifactId>agentscope-anthropic-spring-boot-starter</artifactId>
    <version>${agentscope.version}</version>
</dependency>
```

Full builder options, formatters, credentials, and registry context details are covered in [Model](../../docs/building-blocks/model.md).
