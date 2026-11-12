---
title: Gemini
zh_link: /v2/zh/integration/model/gemini
---

`agentscope-extensions-model-gemini` integrates Google Gemini models through the Gemini API and supports the Vertex AI path through explicit configuration.

## Add the dependency

```xml
<dependency>
    <groupId>io.agentscope</groupId>
    <artifactId>agentscope-extensions-model-gemini</artifactId>
    <version>${agentscope.version}</version>
</dependency>
```

## ModelRegistry

Set `GEMINI_API_KEY`, then use the `gemini:<model>` id:

```java
ReActAgent agent = ReActAgent.builder()
    .name("assistant")
    .model("gemini:gemini-2.0-flash") // Resolved internally by ModelRegistry.resolve(modelId)
    .build();
```

## Explicit builder

Use the builder when you need custom API settings, Vertex AI credentials, formatter, transport, or generation options:

```java
import io.agentscope.extensions.model.gemini.GeminiChatModel;

GeminiChatModel model = GeminiChatModel.builder()
    .apiKey(System.getenv("GEMINI_API_KEY"))
    .modelName("gemini-2.0-flash")
    .streamEnabled(true)
    .build();
```

## Server-side tools

Gemini built-in tools run on the model provider instead of the local AgentScope toolkit. Build a
Google GenAI SDK `Tool` definition, then wrap it with `GeminiServerTool.of(...)`:

```java
import io.agentscope.extensions.model.gemini.tool.GeminiServerTool;
import com.google.genai.types.GoogleSearch;
import com.google.genai.types.Tool;
import com.google.genai.types.UrlContext;

GeminiChatModel model = GeminiChatModel.builder()
    .apiKey(System.getenv("GEMINI_API_KEY"))
    .modelName("gemini-3.8-flash")
    .serverTools(List.of(
        GeminiServerTool.of(Tool.builder()
            .googleSearch(GoogleSearch.builder().build())
            .build()),
        GeminiServerTool.of(Tool.builder()
            .urlContext(UrlContext.builder())
            .build())
    ))
    .build();
```

When at least one server-side tool is configured, AgentScope enables Gemini server-side invocation
context automatically and preserves returned calls and results in conversation history. Server-side
tools can be combined with local function tools. Do not use `GeminiServerTool.of(...)` for custom
function tools; it rejects those definitions.

## Documents

Gemini reads documents natively. A `DocumentBlock` on a user message becomes an `inlineData` part
(bytes from a `Base64Source` or a readable `URLSource`) or a `fileData` part when the source is a
`FileIdSource` whose provider is `gemini` — bare Files API names such as `files/abc123` are expanded
to the full URI `fileData` expects.

The API only reads the document types listed in the `generateContent` reference — `application/pdf`,
`application/json`, plain text and code formats (`text/plain`, `text/html`, `text/css`,
`text/markdown`, `text/csv`, `text/xml`, `text/rtf`, `text/javascript`, `text/x-typescript`,
`text/x-python`, and the `application/x-*` code equivalents). A document declaring any other MIME
type (a `.docx`, for example) is degraded to a text placeholder instead of being sent, because the
API would reject the whole request otherwise.

## Spring Boot

Spring Boot applications can use the Gemini starter:

```xml
<dependency>
    <groupId>io.agentscope</groupId>
    <artifactId>agentscope-gemini-spring-boot-starter</artifactId>
    <version>${agentscope.version}</version>
</dependency>
```

Full builder options, formatters, credentials, and registry context details are covered in [Model](/v2/en/docs/building-blocks/model).
