---
title: "Model"
description: "Configure and connect LLM model providers in AgentScope Java"
---

## Overview

The model layer is two-tiered: at the top sit **Credentials** (`io.agentscope.core.credential`), which carry a provider's API auth fields; below them sit **Chat Models** (`io.agentscope.core.model`), the concrete inference implementations attached to a credential.

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

## Chat model

A **Chat Model** is the LLM driving conversation and tool calling, with input and output potentially spanning multiple modalities. AgentScope Java currently ships:

| Provider | Class | Notes |
|----------|-------|-------|
| OpenAI | `OpenAIChatModel` | Chat Completions API; works with vLLM and OpenAI-compatible endpoints (DeepSeek, Kimi, …) |
| Anthropic | `AnthropicChatModel` | Claude models; prompt caching and thinking |
| DashScope | `DashScopeChatModel` | Qwen models; multi-modal (vision/audio/video), reasoning |
| Gemini | `GeminiChatModel` | Google Gemini; multi-modal |
| Ollama | `OllamaChatModel` | Locally hosted LLMs; credential optional |

Credential classes: `OpenAICredential`, `AnthropicCredential`, `DashScopeCredential`, `GeminiCredential`, `OllamaCredential`, `DeepSeekCredential`, `KimiCredential`, `XAICredential`.

### Creating a chat model

Each chat model is built with a builder. The most common fields are `apiKey`, `modelName`, `stream`, `formatter`, `defaultOptions`. Three typical setups:

::::{tab-set}
:::{tab-item} Streaming
```java
import io.agentscope.core.formatter.dashscope.DashScopeChatFormatter;
import io.agentscope.core.model.DashScopeChatModel;

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
import io.agentscope.core.formatter.dashscope.DashScopeChatFormatter;
import io.agentscope.core.model.DashScopeChatModel;
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
import io.agentscope.core.formatter.dashscope.DashScopeChatFormatter;
import io.agentscope.core.model.DashScopeChatModel;
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
import io.agentscope.core.model.DashScopeChatModel;
import io.agentscope.core.model.GenerateOptions;
import io.agentscope.core.formatter.dashscope.DashScopeChatFormatter;
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

> **Structured output with tool calling**
>
> When an agent has both tools and structured output, some OpenAI-compatible providers (e.g. Kimi, Deepseek) prioritise the `response_format` constraint and skip tool calling entirely. If you encounter this, set `nativeStructuredOutputWithTools(false)` when building the model — the framework will use a synthetic tool approach for structured output, fully compatible with the ReAct tool-calling loop:
>
> ```java
> OpenAIChatModel model = OpenAIChatModel.builder()
>         .apiKey("...")
>         .baseUrl("https://api.moonshot.cn/v1")
>         .modelName("moonshot-v1-8k")
>         .nativeStructuredOutputWithTools(false)
>         .build();
> ```
>
> `DashScopeChatModel` supports this option as well. For native OpenAI models (GPT-4o, etc.) the default behavior handles both correctly — no configuration needed.

### Formatter

A **Formatter** converts AgentScope `Msg` objects into the request payload each provider's API expects. It is configured via the chat model builder's `formatter(...)`. Each provider ships two formatters:

| Type | Use case |
|------|----------|
| **ChatFormatter** (default) | Standard single-agent chat. Each `Msg` maps 1:1 to one API message, preserving the role (`USER`, `ASSISTANT`, `SYSTEM`). |
| **MultiAgentFormatter** | Multi-agent scenarios such as debate or moderator setups. Consecutive agent messages are aggregated and tagged with the sender's name. |

To switch to multi-agent mode, just pass the MultiAgent variant — no agent code changes:

```java
import io.agentscope.core.formatter.dashscope.DashScopeMultiAgentFormatter;
import io.agentscope.core.model.DashScopeChatModel;

DashScopeChatModel model =
        DashScopeChatModel.builder()
                .apiKey(System.getenv("DASHSCOPE_API_KEY"))
                .modelName("qwen-plus")
                .stream(true)
                .formatter(new DashScopeMultiAgentFormatter())
                .build();
```

Per-provider formatters (under `io.agentscope.core.formatter`):

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
import io.agentscope.core.credential.AnthropicCredential;
import io.agentscope.core.credential.ModelCard;
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
