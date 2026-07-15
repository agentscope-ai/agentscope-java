---
title: "Model"
description: "Configure and connect LLM model providers in AgentScope Java"
---

## Overview

The model layer separates shared contracts from provider implementations. `agentscope-core` keeps the common APIs (`Model`, `ChatModelBase`, `Formatter`, `ModelRegistry`, and the `ModelProvider` SPI). OpenAI, DashScope, Gemini, Anthropic, and Ollama implementations live in their own model extension modules.

At runtime, the model layer is two-tiered: at the top sit **Credentials** (based on `io.agentscope.core.credential`), which carry a provider's API auth fields; below them sit **Chat Models**, the concrete inference implementations attached to a credential.

```text
CredentialBase/
└── ChatModelBase/
    ├── OpenAIChatModel
    ├── AnthropicChatModel
    ├── DashScopeChatModel
    ├── GeminiChatModel
    └── OllamaChatModel
```

A **Credential** carries a provider's API auth fields (`apiKey`, `baseUrl`, …). Starting from a credential, you can call `listModels()` to enumerate the models available under that provider (returns `Mono<List<ModelCard>>`).

This layering matches the natural UX in a frontend — register the credential first, then pick a model under it — so the UI authenticates once and shows everything that provider supports.

## Model extension modules

Provider-specific model implementations have been moved out of `agentscope-core` into independent extension modules. Each provider module owns its chat model, credential, formatter, DTO, exception, and SDK/API client, etc.

| Provider | Maven artifact | Main package |
|----------|----------------|--------------|
| OpenAI | `agentscope-extensions-model-openai` | `io.agentscope.extensions.model.openai` |
| DashScope | `agentscope-extensions-model-dashscope` | `io.agentscope.extensions.model.dashscope` |
| Gemini | `agentscope-extensions-model-gemini` | `io.agentscope.extensions.model.gemini` |
| Anthropic | `agentscope-extensions-model-anthropic` | `io.agentscope.extensions.model.anthropic` |
| Ollama | `agentscope-extensions-model-ollama` | `io.agentscope.extensions.model.ollama` |

### Migration checklist

1. Add the provider extension module dependency. For example, DashScope:

```xml
<dependency>
    <groupId>io.agentscope</groupId>
    <artifactId>agentscope-extensions-model-dashscope</artifactId>
</dependency>
```

Other provider artifacts follow the same pattern: `agentscope-extensions-model-openai`, `agentscope-extensions-model-gemini`, `agentscope-extensions-model-anthropic`, and `agentscope-extensions-model-ollama`.

2. Replace provider imports from `io.agentscope.core.model.*` with `io.agentscope.extensions.model.<provider>.*`.
3. Replace provider formatter imports from `io.agentscope.core.formatter.<provider>.*` with `io.agentscope.extensions.model.<provider>.formatter.*`.
4. For Spring Boot applications, replace the generic model creation path with the matching provider-specific starter and its `agentscope.<provider>.*` properties.

```xml
<dependency>
    <groupId>io.agentscope</groupId>
    <artifactId>agentscope-dashscope-spring-boot-starter</artifactId>
</dependency>
```

## Choose a creation path

### String model id

For simple non-Spring applications, use a `ModelRegistry` string id such as `dashscope:qwen-plus` or `openai:gpt-4.1-mini`. Add the matching model extension module, set the provider's standard environment variable such as `DASHSCOPE_API_KEY` or `OPENAI_API_KEY`, and pass the id directly to the agent:

```java
ReActAgent agent =
        ReActAgent.builder()
                .name("assistant")
                .model("dashscope:qwen-plus") // resolved internally by ModelRegistry.resolve(modelId)
                .build();
```

The extension module is discovered through Java SPI. The model provider reads its standard environment variables such as `DASHSCOPE_API_KEY`, `OPENAI_API_KEY`, `ANTHROPIC_API_KEY`, or `GEMINI_API_KEY`. Ollama reads `OLLAMA_BASE_URL` when present and otherwise defaults to the local Ollama endpoint.

### Explicit model builder

When you need a custom API key, base URL, formatter, transport, timeout, generation options, or other provider-specific configuration, build the model explicitly and pass the `Model` instance to the agent:

```java
import io.agentscope.extensions.model.dashscope.DashScopeChatModel;
import io.agentscope.extensions.model.dashscope.formatter.DashScopeChatFormatter;

DashScopeChatModel model =
        DashScopeChatModel.builder()
                .apiKey(System.getenv("DASHSCOPE_API_KEY"))
                .modelName("qwen-plus")
                .stream(true)
                .formatter(new DashScopeChatFormatter())
                .build();

ReActAgent agent =
        ReActAgent.builder()
                .name("assistant")
                .model(model)
                .build();
```

### Spring Boot applications

For Spring Boot, prefer provider-specific starters such as `agentscope-openai-spring-boot-starter`, `agentscope-dashscope-spring-boot-starter`, `agentscope-gemini-spring-boot-starter`, `agentscope-anthropic-spring-boot-starter`, and `agentscope-ollama-spring-boot-starter`. These starters directly depend on the matching model extension, create Spring-managed `Model` beans, and leave the generic starter focused on common AgentScope infrastructure. They do not create models through the static `ModelRegistry`; advanced users can always provide their own `Model` bean.

OpenAI example:

```yaml
agentscope:
  model:
    provider: openai
  openai:
    api-key: ${OPENAI_API_KEY}
    model-name: gpt-4.1-mini
    stream: true
```

#### Builder customizers

Provider-specific starters also expose ordered Spring bean customizers for the
auto-configured chat model builders. Use them when property binding covers the common
settings but you still need to tune builder-only options such as custom formatters,
default generation options, proxy/client settings, or provider-specific flags.

| Starter | Customizer type |
|---------|-----------------|
| `agentscope-openai-spring-boot-starter` | `OpenAIChatModelBuilderCustomizer` |
| `agentscope-dashscope-spring-boot-starter` | `DashScopeChatModelBuilderCustomizer` |
| `agentscope-gemini-spring-boot-starter` | `GeminiChatModelBuilderCustomizer` |
| `agentscope-anthropic-spring-boot-starter` | `AnthropicChatModelBuilderCustomizer` |
| `agentscope-ollama-spring-boot-starter` | `OllamaChatModelBuilderCustomizer` |

Customizer beans are applied after starter properties are bound and before
`builder.build()` is called. Multiple customizers are supported and follow Spring's
`@Order` / `Ordered` ordering.

```java
import io.agentscope.core.model.GenerateOptions;
import io.agentscope.spring.boot.openai.OpenAIChatModelBuilderCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.Order;

@Configuration(proxyBeanMethods = false)
class ModelCustomizerConfiguration {

    @Bean
    @Order(0)
    OpenAIChatModelBuilderCustomizer openAIModelDefaults() {
        return builder ->
                builder.defaultOptions(
                        GenerateOptions.builder()
                                .temperature(0.2)
                                .parallelToolCalls(false)
                                .build());
    }
}
```

## ModelRegistry and ModelCreationContext

`ModelRegistry` is a global registry for model instance creation and lookup, supporting multiple resolution strategies. During resolution, it tries in priority order: named model instances directly registered via `ModelRegistry.register(name, model)`, custom factories registered via `registerFactory(regex, factory)`, and `ModelProvider` implementations automatically discovered from extension modules through the Java SPI mechanism.

For simple scenarios, prefer a string id in the `provider:model` format together with the provider's standard environment variable; for fine-grained control, use explicit model builders. `ModelCreationContext` is mainly for integration-layer code that must resolve models dynamically.

### Advanced integration context

`ModelCreationContext` is for integration layers that must create models dynamically without importing a concrete provider builder, such as multi-tenant gateways, plugin systems, or framework adapters. It can pass common values such as API key, base URL, endpoint path, stream mode, and extension-defined options/components to the SPI provider:

```java
import io.agentscope.core.model.GenerateOptions;
import io.agentscope.core.model.Model;
import io.agentscope.core.model.ModelCreationContext;
import io.agentscope.core.model.ModelRegistry;

ModelCreationContext context =
        ModelCreationContext.builder()
                .apiKey(tenantApiKey)
                .baseUrl(tenantBaseUrl)
                .stream(false)
                // Extension-defined scalar options, keyed by names the provider documents.
                .option("contextWindowSize", 128000)
                // Type-keyed components for richer provider settings, transports, or formatters.
                .component(
                        GenerateOptions.class,
                        GenerateOptions.builder()
                                .parallelToolCalls(false)
                                .build())
                .build();

Model model = ModelRegistry.resolve("openai:gpt-4.1-mini", context);
```

### Cache policy

`ModelRegistry` caches models resolved from simple `provider:model` strings. Context-aware creation is not cached by default to avoid reusing a model instance with a different tenant's API key, base URL, or stream setting.

| Policy | Behavior |
|--------|----------|
| `DEFAULT` | `resolve(String)` keeps legacy model-id caching. `resolve(String, nonEmptyContext)` is not cached. |
| `DISABLED` | Never cache; every resolution creates a new model instance. |
| `ENABLED` | Cache only when the caller explicitly opts in. Use `cacheId(...)` for tenant- or configuration-specific identity. |

If `CachePolicy.ENABLED` is used with `option(...)` or `component(...)`, the user must provide a `cacheId`.

### ModelProvider SPI

Provider extension modules are discovered with Java SPI through `META-INF/services/io.agentscope.core.model.spi.ModelProvider`. A provider can implement `supports(String, ModelCreationContext)` and `create(String, ModelCreationContext)` to consume context values. Simple providers can keep implementing the original `supports(String)` and `create(String)` methods because the context-aware methods have compatible defaults.

## Chat model

A **Chat Model** is the LLM driving conversation and tool calling, with input and output potentially spanning multiple modalities. AgentScope Java currently ships:

| Provider | Class | Notes |
|----------|-------|-------|
| OpenAI | `OpenAIChatModel` | Chat Completions API; works with vLLM and OpenAI-compatible endpoints (DeepSeek, Kimi, …) |
| Anthropic | `AnthropicChatModel` | Claude models; prompt caching and thinking |
| DashScope | `DashScopeChatModel` | Qwen models; multi-modal (vision/audio/video), reasoning |
| Gemini | `GeminiChatModel` | Google Gemini; multi-modal |
| Ollama | `OllamaChatModel` | Locally hosted LLMs; credential optional |

Provider credential classes live with their model extension modules, for example `OpenAICredential`, `AnthropicCredential`, `DashScopeCredential`, `GeminiCredential`, and `OllamaCredential`. OpenAI-compatible credentials such as `DeepSeekCredential`, `KimiCredential`, and `XAICredential` remain available from core.

### Creating a chat model

Each chat model is built with a builder. The most common fields are `apiKey`, `modelName`, `stream`, `formatter`, `defaultOptions`. Three typical setups:

::::{tab-set}
:::{tab-item} Streaming
```java
import io.agentscope.extensions.model.dashscope.formatter.DashScopeChatFormatter;
import io.agentscope.extensions.model.dashscope.DashScopeChatModel;

DashScopeChatModel model =
        DashScopeChatModel.builder()
                .apiKey(System.getenv("DASHSCOPE_API_KEY"))
                .modelName("qwen-plus")
                .stream(true)
                .formatter(new DashScopeChatFormatter())
                .build();
```
:::
:::{tab-item} Tools
```java
import io.agentscope.extensions.model.dashscope.formatter.DashScopeChatFormatter;
import io.agentscope.extensions.model.dashscope.DashScopeChatModel;
import io.agentscope.core.model.GenerateOptions;

DashScopeChatModel model =
        DashScopeChatModel.builder()
                .apiKey(System.getenv("DASHSCOPE_API_KEY"))
                .modelName("qwen-plus")
                .stream(false)
                .formatter(new DashScopeChatFormatter())
                .defaultOptions(
                        GenerateOptions.builder()
                                .parallelToolCalls(false)
                                .build())
                .build();
```
:::
:::{tab-item} Reasoning
```java
import io.agentscope.extensions.model.dashscope.formatter.DashScopeChatFormatter;
import io.agentscope.extensions.model.dashscope.DashScopeChatModel;
import io.agentscope.core.model.GenerateOptions;

DashScopeChatModel model =
        DashScopeChatModel.builder()
                .apiKey(System.getenv("DASHSCOPE_API_KEY"))
                .modelName("qwen3-235b-a22b-thinking-2507")
                .stream(true)
                .enableThinking(true)
                .formatter(new DashScopeChatFormatter())
                .defaultOptions(
                        GenerateOptions.builder()
                                .thinkingBudget(2048)
                                .build())
                .build();
```
:::
::::

Common builder fields:

| Field | Type | Description |
|-------|------|-------------|
| `apiKey` | `String` | API key (some providers also accept `credential(...)`) |
| `modelName` | `String` | Model identifier (e.g. `"qwen-plus"`) |
| `stream` | `boolean` | Whether to stream output |
| `defaultOptions` | `GenerateOptions` | Provider-specific options (`temperature`, `maxTokens`, `thinkingBudget`, `parallelToolCalls`, …) |
| `formatter` | `Formatter` | Override the default message formatter |
| `baseUrl` | `String` | Custom service endpoint (e.g. an OpenAI-compatible proxy) |

### Calling a chat model

The `Model` interface exposes a unified `stream(messages, tools, options)` returning `Flux<ChatResponse>`:

```java
import io.agentscope.core.message.UserMessage;
import io.agentscope.core.model.ChatResponse;
import io.agentscope.extensions.model.dashscope.DashScopeChatModel;
import io.agentscope.core.model.GenerateOptions;
import io.agentscope.extensions.model.dashscope.formatter.DashScopeChatFormatter;
import java.util.List;

DashScopeChatModel model =
        DashScopeChatModel.builder()
                .apiKey(System.getenv("DASHSCOPE_API_KEY"))
                .modelName("qwen-plus")
                .stream(true)
                .formatter(new DashScopeChatFormatter())
                .build();

model.stream(
                List.of(new UserMessage("Count from 1 to 5.")),
                /* tools = */ List.of(),
                GenerateOptions.builder().build())
        .doOnNext(chunk -> System.out.println("Chunk: " + chunk.getContent()))
        .doOnComplete(() -> System.out.println("Stream completed"))
        .blockLast();
```

A `ChatResponse` carries a list of content blocks (`TextBlock`, `ThinkingBlock`, `ToolUseBlock`, `DataBlock`) and a `ChatUsage` recording token counts and timing.

In practice you usually call models indirectly via `ReActAgent`. For lightweight direct invocation, see `agentscope-examples/documentation/.../model/ModelRegistryExample.java`.

### Generating structured output

The agent layer offers a convenience overload for binding the model output to a Java POJO via `ReActAgent.call(msgs, structuredOutputClass, runtimeContext)`:

```java
import io.agentscope.core.ReActAgent;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.UserMessage;
import java.util.List;

public class WeatherInfo {
    public String city;
    public double temperature;
    public String unit;
}

Msg msg =
        agent.call(
                        List.of(new UserMessage("What's the weather in Shanghai?")),
                        WeatherInfo.class,
                        RuntimeContext.empty())
                .block();

WeatherInfo info = msg.getStructuredData(WeatherInfo.class);
```

How it works: the framework synthesizes a forced structured tool call from the target class, validates and repairs the model output, and writes the result into `Msg.metadata` under the `structured_output` key, so `getStructuredData(Class)` can deserialize it directly. Complete example: `agentscope-examples/documentation/.../structuredoutput/StructuredOutputExample.java`.

#### Structured output path selection

The framework provides two structured output paths:

| Path | Condition | Mechanism |
|------|-----------|-----------|
| **Native** | `supportsNativeStructuredOutput() = true` | Uses `response_format` + `json_schema` for direct JSON output |
| **Fallback** (default) | `supportsNativeStructuredOutput() = false` | Injects a `generate_response` synthetic tool; model returns structured data via tool call |

If the native path fails (e.g. model returns HTTP 400), the framework **automatically falls back** to the synthetic tool path — no user intervention needed.

#### Default behavior per provider

| Provider | `supportsNativeStructuredOutput` | Notes |
|----------|----------------------------------|-------|
| OpenAI (GPT-4o, etc.) | `true` | Native `json_schema` support |
| OpenAI (DeepSeek/GLM formatter) | `false` | Not supported; auto-fallback |
| DashScope | `false` | Native endpoint only supports `json_object`, not `json_schema`; fallback by default |
| Anthropic | `false` (default) | — |

> **DashScope users**: Thinking mode (`enableThinking(true)`) does not support structured output at all — the framework forces the fallback path.

#### Explicit configuration

If you confirm your model/endpoint supports `json_schema`, enable the native path via builder:

```java
DashScopeChatModel model = DashScopeChatModel.builder()
        .apiKey(System.getenv("DASHSCOPE_API_KEY"))
        .modelName("qwen-plus")
        .nativeStructuredOutput(true)  // explicitly enable native json_schema path
        .build();
```

#### Structured output with tool calling

When an agent has both tools and structured output, some OpenAI-compatible providers (e.g. Kimi, Deepseek) prioritise the `response_format` constraint and skip tool calling entirely. Set `nativeStructuredOutputWithTools(false)` to resolve this:

```java
OpenAIChatModel model = OpenAIChatModel.builder()
        .apiKey("...")
        .baseUrl("https://api.moonshot.cn/v1")
        .modelName("moonshot-v1-8k")
        .nativeStructuredOutputWithTools(false)
        .build();
```

`DashScopeChatModel` supports this option as well. For native OpenAI models (GPT-4o, etc.) the default behavior handles both correctly — no configuration needed.

### Formatter

A **Formatter** converts AgentScope `Msg` objects into the request payload each provider's API expects. It is configured via the chat model builder's `formatter(...)`. Each provider ships two formatters:

| Type | Use case |
|------|----------|
| **ChatFormatter** (default) | Standard single-agent chat. Each `Msg` maps 1:1 to one API message, preserving the role (`USER`, `ASSISTANT`, `SYSTEM`). |
| **MultiAgentFormatter** | Multi-agent scenarios such as debate or moderator setups. Consecutive agent messages are aggregated and tagged with the sender's name. |

To switch to multi-agent mode, just pass the MultiAgent variant — no agent code changes:

```java
import io.agentscope.extensions.model.dashscope.formatter.DashScopeMultiAgentFormatter;
import io.agentscope.extensions.model.dashscope.DashScopeChatModel;

DashScopeChatModel model =
        DashScopeChatModel.builder()
                .apiKey(System.getenv("DASHSCOPE_API_KEY"))
                .modelName("qwen-plus")
                .stream(true)
                .formatter(new DashScopeMultiAgentFormatter())
                .build();
```

Per-provider formatters now live with their provider extension modules:

| Provider | Chat | MultiAgent |
|----------|------|------------|
| DashScope | `DashScopeChatFormatter` | `DashScopeMultiAgentFormatter` |
| OpenAI | `OpenAIChatFormatter` | `OpenAIMultiAgentFormatter` |
| Anthropic | `AnthropicChatFormatter` | `AnthropicMultiAgentFormatter` |
| Gemini | `GeminiChatFormatter` | `GeminiMultiAgentFormatter` |
| Ollama | `OllamaChatFormatter` | `OllamaMultiAgentFormatter` |

If your provider's payload doesn't fit any of these, implement the `Formatter<TReq, TResp, TParams>` interface (`io.agentscope.core.formatter`) and pass it through the same `formatter(...)` builder.

### Custom provider

The minimal path to a new provider: implement a `CredentialBase` subclass and a `ChatModelBase` subclass.

#### Step 1: Define the credential

Extend `CredentialBase` and implement `getChatModelClass()`:

```java
import io.agentscope.core.credential.CredentialBase;
import io.agentscope.core.model.ChatModelBase;

public class MyProviderCredential extends CredentialBase {

    private final String apiKey;
    private final String baseUrl;

    public MyProviderCredential(String apiKey, String baseUrl) {
        super("my_provider:" + apiKey.substring(0, Math.min(4, apiKey.length())));
        this.apiKey = apiKey;
        this.baseUrl = baseUrl == null ? "https://api.myprovider.com/v1" : baseUrl;
    }

    public String getApiKey() {
        return apiKey;
    }

    public String getBaseUrl() {
        return baseUrl;
    }

    @Override
    public Class<? extends ChatModelBase> getChatModelClass() {
        return MyProviderChatModel.class;
    }
}
```

#### Step 2: Implement the chat model

Extend `ChatModelBase` and implement `doStream`:

```java
import io.agentscope.core.message.Msg;
import io.agentscope.core.model.ChatModelBase;
import io.agentscope.core.model.ChatResponse;
import io.agentscope.core.model.GenerateOptions;
import io.agentscope.core.model.ToolSchema;
import java.util.List;
import reactor.core.publisher.Flux;

public class MyProviderChatModel extends ChatModelBase {

    private final MyProviderCredential credential;
    private final String modelName;

    public MyProviderChatModel(MyProviderCredential credential, String modelName) {
        this.credential = credential;
        this.modelName = modelName;
    }

    @Override
    protected Flux<ChatResponse> doStream(
            List<Msg> messages, List<ToolSchema> tools, GenerateOptions options) {
        // Call the provider's API, wrap responses into a Flux<ChatResponse>.
        return Flux.empty();
    }
}
```

#### Step 3: Register with the ModelRegistry (optional)

`ModelRegistry` lets `ReActAgent.builder().model("provider:model-name")` resolve models from a string:

```java
import io.agentscope.core.model.ModelRegistry;

ModelRegistry.registerFactory(
        "myprov:.*",
        modelId -> new MyProviderChatModel(
                new MyProviderCredential(System.getenv("MYPROV_API_KEY"), null),
                modelId.substring("myprov:".length())));

// Then:
// ReActAgent.builder().model("myprov:my-model-v1")...
```

## Frontend integration

### What is ModelCard

`ModelCard` (`credential/ModelCard.java`) is a declarative description of a model's capabilities and constraints. It powers frontends — the model picker, parameter form, and capability toggles can render dynamically against it without hard-coding any provider-specific logic.

Today, `ModelCard` is a minimal record:

| Method | Type | Description |
|--------|------|-------------|
| `modelName()` | `String` | Model identifier (e.g. `"claude-sonnet-4-6"`) |
| `displayName()` | `String` | Human-readable label (e.g. `"Claude Sonnet 4.6"`) |
| `contextSize()` | `Integer` | Maximum context window (in tokens) |

:::{note}
The `ModelCard` schema is intentionally minimal at this stage; capability flags (input/output MIME types) and parameter schemas will be added as model-discovery infrastructure matures.
:::

### Fetching ModelCards

Call `CredentialBase#listModels()`, returning `Mono<List<ModelCard>>`:

```java
import io.agentscope.core.credential.ModelCard;
import io.agentscope.extensions.model.anthropic.credential.AnthropicCredential;
import java.util.List;

AnthropicCredential cred = new AnthropicCredential(System.getenv("ANTHROPIC_API_KEY"));
List<ModelCard> cards = cred.listModels().block();

for (ModelCard card : cards) {
    System.out.println(
            card.modelName() + ": context=" + card.contextSize());
}
```

`getChatModelClass()` returns the matching `ChatModelBase` subclass — useful for reflectively building a default model:

```java
Class<? extends io.agentscope.core.model.ChatModelBase> modelCls = cred.getChatModelClass();
```

This design lets frontends discover every model available under a provider with just one credential — no hard-coded provider logic.
