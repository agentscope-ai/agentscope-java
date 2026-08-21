# Gemini Model

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

Gemini built-in tools run on the model provider instead of through the local AgentScope toolkit.
Configure them on the explicit model builder:

```java
import io.agentscope.extensions.model.gemini.GeminiChatModel;
import io.agentscope.extensions.model.gemini.tool.GeminiServerTool;
import java.util.List;

GeminiChatModel model = GeminiChatModel.builder()
    .apiKey(System.getenv("GEMINI_API_KEY"))
    .modelName("gemini-2.0-flash")
    .serverTools(List.of(
        GeminiServerTool.googleSearch()
            .param("excludeDomains", List.of("example.com"))
            .build(),
        GeminiServerTool.urlContext().build()
    ))
    .build();
```

| Tool | Configuration and supported parameters |
| --- | --- |
| Google Search | `GeminiServerTool.googleSearch()`; `searchTypes`, `blockingConfidence`, `excludeDomains`, `timeRangeFilter` |
| Google Maps | `GeminiServerTool.googleMap()`; `authConfig`, `enableWidget` |
| URL Context | `GeminiServerTool.urlContext()`; no parameters |
| Code Execution | `GeminiServerTool.builder().type(GeminiServerTool.CODE_EXECUTION)`; no parameters |

When at least one server-side tool is configured, AgentScope enables Gemini server-side invocation
context automatically and preserves returned calls and results in conversation history. Server-side
tools remain separate from local function tools, so both kinds can be provided in the same request.

## Spring Boot

Spring Boot applications can use the Gemini starter:

```xml
<dependency>
    <groupId>io.agentscope</groupId>
    <artifactId>agentscope-gemini-spring-boot-starter</artifactId>
    <version>${agentscope.version}</version>
</dependency>
```

Full builder options, formatters, credentials, and registry context details are covered in [Model](../../docs/building-blocks/model.md).
