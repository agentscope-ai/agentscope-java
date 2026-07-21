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

See [Gemini End-to-End Request Flow](gemini-request-flow.md) for the complete single-request path.

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
