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

## Prompt caching

### Implicit caching

Gemini 2.5 and newer models enable implicit caching on the provider side. No request field is required. Setting `GenerateOptions.cacheControl(true)` does not create a Gemini cache resource, and setting it to `false` does not disable provider-managed implicit caching.

Likewise, adding `MessageMetadataKeys.CACHE_CONTROL` to a `Msg` does not create or manage a Gemini `cachedContents` resource. Gemini explicit caching has a separate, provider-managed resource lifecycle.

### Reference explicit cached content

Create and manage the cache resource with the Google Gen AI SDK, then pass its resource name through `GenerateOptions`:

```java
GenerateOptions options = GenerateOptions.builder()
    .additionalBodyParam("cachedContent", cache.name().orElseThrow())
    .build();

model.stream(dynamicMessages, tools, options);
```

The resource name is passed to `GenerateContentConfig.cachedContent(...)` unchanged. Use a cache created for the same model and, for Vertex AI, the same project and location. The resource must also be unexpired.

Cached content is a prefix to the request. Pass only the dynamic suffix in `dynamicMessages`; do not resend messages already stored in the cache. If the request uses a system instruction, tool declarations, or tool configuration, include them when creating the cache resource. Gemini does not allow these fields to be sent again in a `generateContent` request that references cached content. Tool schemas may still be supplied to AgentScope for local tool execution, but they must match the declarations stored in the cache.

The Google SDK owns cache creation, TTL updates, lookup, and deletion. AgentScope only references the supplied resource name during generation.

### Cache usage

Gemini cache usage is normalized into `ChatUsage` as follows:

- `inputTokens` is the total effective prompt size and already includes cached tokens.
- `cachedTokens` is the cached subset reported by Gemini's `cachedContentTokenCount`.
- `cacheCreationInputTokens` is `0` for `generateContent`; cache creation is a separate resource operation.

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
