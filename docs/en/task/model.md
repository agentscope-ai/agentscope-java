# Model

This guide introduces the LLM models supported by AgentScope Java and how to configure them.

## Supported Models

| Provider   | Class                   | Streaming | Tools | Vision | Reasoning |
|------------|-------------------------|-----------|-------|--------|-----------|
| DashScope  | `DashScopeChatModel`    | ✅        | ✅    | ✅     | ✅        |
| OpenAI     | `OpenAIChatModel`       | ✅        | ✅    | ✅     |           |
| Anthropic  | `AnthropicChatModel`    | ✅        | ✅    | ✅     | ✅        |
| Gemini     | `GeminiChatModel`       | ✅        | ✅    | ✅     | ✅        |

> **Note**:
> - `OpenAIChatModel` is compatible with OpenAI API specification, works with vLLM, DeepSeek, etc.
> - `GeminiChatModel` supports both Gemini API and Vertex AI

## Getting API Keys

| Provider | URL | Environment Variable |
|----------|-----|----------------------|
| DashScope | [Alibaba Cloud Bailian Console](https://bailian.console.aliyun.com/) | `DASHSCOPE_API_KEY` |
| OpenAI | [OpenAI Platform](https://platform.openai.com/api-keys) | `OPENAI_API_KEY` |
| Anthropic | [Anthropic Console](https://console.anthropic.com/settings/keys) | `ANTHROPIC_API_KEY` |
| Gemini | [Google AI Studio](https://aistudio.google.com/apikey) | `GEMINI_API_KEY` |
| DeepSeek | [DeepSeek Platform](https://platform.deepseek.com/api_keys) | - |

## DashScope

Alibaba Cloud LLM platform, providing Qwen series models.

```java
DashScopeChatModel model = DashScopeChatModel.builder()
        .apiKey(System.getenv("DASHSCOPE_API_KEY"))
        .modelName("qwen3-max")
        .build();
```

### Configuration

| Option | Description |
|--------|-------------|
| `apiKey` | DashScope API key |
| `modelName` | Model name, e.g., `qwen3-max`, `qwen-vl-max` |
| `baseUrl` | Custom API endpoint (optional) |
| `stream` | Enable streaming, default `true` |
| `enableThinking` | Enable thinking mode to show reasoning process |
| `enableSearch` | Enable web search for real-time information |

### Thinking Mode

```java
DashScopeChatModel model = DashScopeChatModel.builder()
        .apiKey(System.getenv("DASHSCOPE_API_KEY"))
        .modelName("qwen3-max")
        .enableThinking(true)  // Automatically enables streaming
        .defaultOptions(GenerateOptions.builder()
                .thinkingBudget(5000)  // Token budget for thinking
                .build())
        .build();
```

## OpenAI

OpenAI models and compatible APIs.

```java
OpenAIChatModel model = OpenAIChatModel.builder()
        .apiKey(System.getenv("OPENAI_API_KEY"))
        .modelName("gpt-4o")
        .build();
```

### Compatible APIs

For DeepSeek, vLLM, and other compatible providers:

```java
OpenAIChatModel model = OpenAIChatModel.builder()
        .apiKey("your-api-key")
        .modelName("deepseek-chat")
        .baseUrl("https://api.deepseek.com")
        .build();
```

### Configuration

| Option | Description |
|--------|-------------|
| `apiKey` | API key |
| `modelName` | Model name, e.g., `gpt-4o`, `gpt-4o-mini` |
| `baseUrl` | Custom API endpoint (optional) |
| `stream` | Enable streaming, default `true` |

## Anthropic

Anthropic's Claude series models.

```java
AnthropicChatModel model = AnthropicChatModel.builder()
        .apiKey(System.getenv("ANTHROPIC_API_KEY"))
        .modelName("claude-sonnet-4-5-20250929")  // Default
        .build();
```

### Configuration

| Option | Description |
|--------|-------------|
| `apiKey` | Anthropic API key |
| `modelName` | Model name, default `claude-sonnet-4-5-20250929` |
| `baseUrl` | Custom API endpoint (optional) |
| `stream` | Enable streaming, default `true` |

## Gemini

Google's Gemini series models, supporting both Gemini API and Vertex AI.

### Gemini API

```java
GeminiChatModel model = GeminiChatModel.builder()
        .apiKey(System.getenv("GEMINI_API_KEY"))
        .modelName("gemini-2.5-flash")  // Default
        .build();
```

### Vertex AI

```java
GeminiChatModel model = GeminiChatModel.builder()
        .modelName("gemini-2.0-flash")
        .project("your-gcp-project")
        .location("us-central1")
        .vertexAI(true)
        .credentials(GoogleCredentials.getApplicationDefault())
        .build();
```

### Configuration

| Option | Description |
|--------|-------------|
| `apiKey` | Gemini API key |
| `modelName` | Model name, default `gemini-2.5-flash` |
| `project` | GCP project ID (Vertex AI) |
| `location` | GCP region (Vertex AI) |
| `vertexAI` | Whether to use Vertex AI |
| `credentials` | GCP credentials (Vertex AI) |
| `streamEnabled` | Enable streaming, default `true` |

## Generation Options

Configure generation parameters with `GenerateOptions`:

```java
GenerateOptions options = GenerateOptions.builder()
        .temperature(0.7)           // Randomness (0.0-2.0)
        .topP(0.9)                  // Nucleus sampling
        .topK(40)                   // Top-K sampling
        .maxTokens(2000)            // Maximum output tokens
        .seed(42L)                  // Random seed
        .toolChoice(new ToolChoice.auto())  // Tool choice strategy
        .build();

DashScopeChatModel model = DashScopeChatModel.builder()
        .apiKey(System.getenv("DASHSCOPE_API_KEY"))
        .modelName("qwen3-max")
        .defaultOptions(options)
        .build();
```

### Parameters

| Parameter | Type | Description |
|-----------|------|-------------|
| `temperature` | Double | Controls randomness, 0.0-2.0 |
| `topP` | Double | Nucleus sampling threshold, 0.0-1.0 |
| `topK` | Integer | Limits candidate tokens |
| `maxTokens` | Integer | Maximum tokens to generate |
| `thinkingBudget` | Integer | Token budget for thinking |
| `seed` | Long | Random seed |
| `toolChoice` | ToolChoice | Tool choice strategy |

### Tool Choice Strategy

```java
ToolChoice.auto()              // Model decides (default)
ToolChoice.none()              // Disable tool calling
ToolChoice.required()          // Force tool calling
ToolChoice.specific("tool_name")  // Force specific tool
```

### Additional Parameters

Support for provider-specific parameters:

```java
GenerateOptions options = GenerateOptions.builder()
        .additionalHeader("X-Custom-Header", "value")
        .additionalBodyParam("custom_param", "value")
        .additionalQueryParam("version", "v2")
        .build();
```

## Timeout and Retry

```java
ExecutionConfig execConfig = ExecutionConfig.builder()
        .timeout(Duration.ofMinutes(2))
        .maxAttempts(3)
        .initialBackoff(Duration.ofSeconds(1))
        .maxBackoff(Duration.ofSeconds(10))
        .backoffMultiplier(2.0)
        .build();

GenerateOptions options = GenerateOptions.builder()
        .executionConfig(execConfig)
        .build();
```

## Formatter

Formatter converts AgentScope's unified message format to each LLM provider's API format. Each provider has two types of Formatter:

| Provider | Single-Agent | Multi-Agent |
|----------|--------------|-------------|
| DashScope | `DashScopeChatFormatter` | `DashScopeMultiAgentFormatter` |
| OpenAI | `OpenAIChatFormatter` | `OpenAIMultiAgentFormatter` |
| Anthropic | `AnthropicChatFormatter` | `AnthropicMultiAgentFormatter` |
| Gemini | `GeminiChatFormatter` | `GeminiMultiAgentFormatter` |

### Default Behavior

When no Formatter is specified, the model uses the corresponding `ChatFormatter`, suitable for single-agent scenarios.

### Multi-Agent Scenarios

In multi-agent collaboration (such as Pipeline, MsgHub), use `MultiAgentFormatter`. It will:

- Merge messages from multiple agents into conversation history
- Use `<history></history>` tags to structure historical messages
- Distinguish between current agent and other agents' messages

```java
// DashScope multi-agent
DashScopeChatModel model = DashScopeChatModel.builder()
        .apiKey(System.getenv("DASHSCOPE_API_KEY"))
        .modelName("qwen3-max")
        .formatter(new DashScopeMultiAgentFormatter())
        .build();

// OpenAI multi-agent
OpenAIChatModel model = OpenAIChatModel.builder()
        .apiKey(System.getenv("OPENAI_API_KEY"))
        .modelName("gpt-4o")
        .formatter(new OpenAIMultiAgentFormatter())
        .build();

// Anthropic multi-agent
AnthropicChatModel model = AnthropicChatModel.builder()
        .apiKey(System.getenv("ANTHROPIC_API_KEY"))
        .formatter(new AnthropicMultiAgentFormatter())
        .build();

// Gemini multi-agent
GeminiChatModel model = GeminiChatModel.builder()
        .apiKey(System.getenv("GEMINI_API_KEY"))
        .formatter(new GeminiMultiAgentFormatter())
        .build();
```

### Custom History Prompt

You can customize the conversation history prompt:

```java
String customPrompt = "# Conversation Record\nBelow is the previous conversation:\n";

DashScopeChatModel model = DashScopeChatModel.builder()
        .apiKey(System.getenv("DASHSCOPE_API_KEY"))
        .modelName("qwen3-max")
        .formatter(new DashScopeMultiAgentFormatter(customPrompt))
        .build();
```

### When to Use MultiAgentFormatter

| Scenario | Recommended Formatter |
|----------|----------------------|
| Single-agent conversation | `ChatFormatter` (default) |
| Pipeline sequential execution | `MultiAgentFormatter` |
| MsgHub group chat | `MultiAgentFormatter` |
| Multi-agent debate | `MultiAgentFormatter` |
