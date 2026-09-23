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

## Thinking

Gemini thinking is configured through `GenerateOptions`:

```java
import io.agentscope.core.model.GenerateOptions;

GenerateOptions thinkingOptions = GenerateOptions.builder()
    .thinkingLevel("high")
    .includeThoughts(true)
    .build();
```

- `thinkingBudget(Integer)` sets a token budget for models that support budget-based thinking.
- `thinkingLevel(String)` sets a model-supported level such as `"minimal"`, `"low"`, `"medium"`, or `"high"`.
- `includeThoughts(Boolean)` independently controls whether thoughts are included in the response.

Use either `thinkingBudget` or `thinkingLevel` according to the selected model. For backward compatibility, setting only `thinkingBudget` also enables `includeThoughts`; setting `includeThoughts` explicitly always takes precedence.

The chat formatter replays historical assistant `ThinkingBlock` content and preserves Gemini `thoughtSignature` values on thinking, text, and tool-call parts, including after message JSON serialization. Keep content-block metadata intact when storing or transforming conversation history.

A signature that cannot be restored after persistence (for example, a corrupted or non-Base64 value) is never sent: it is dropped with a warning and the request proceeds without it. The consequences differ by block type. Thinking parts degrade gracefully to ordinary text parts. Function-call parts, however, rely on their signature: Gemini 3 may reject a function call that lost its `thoughtSignature` (typically with a 400 error) once a persisted session resumes. If that happens, remove or regenerate the affected turn instead of retrying it as-is.

See [Gemini End-to-End Request Flow](/v2/en/integration/model/gemini-request-flow) for the complete single-request path.

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
