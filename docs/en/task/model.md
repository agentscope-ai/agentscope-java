# Model

This guide introduces the LLM model APIs integrated in AgentScope Java and how to use them.

## Supported Models

AgentScope Java currently supports two major LLM providers:

| Provider   | Class                 | Streaming | Tools | Vision | Reasoning |
|------------|-----------------------|-----------|-------|--------|-----------|
| DashScope  | `DashScopeChatModel`  | ✅        | ✅    | ✅     | ✅        |
| OpenAI     | `OpenAIChatModel`     | ✅        | ✅    | ✅     |           |

> **Note**: `OpenAIChatModel` is compatible with OpenAI-compatible APIs, including vLLM, DeepSeek, and other providers that implement the OpenAI API specification.

## DashScope Model

DashScope is Alibaba Cloud's LLM platform, providing access to Qwen series models.

### Basic Usage

```java
import io.agentscope.core.model.DashScopeChatModel;
import io.agentscope.core.model.ChatResponse;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import io.agentscope.core.message.TextBlock;
import java.util.List;

public class DashScopeExample {
    public static void main(String[] args) {
        // Create model
        DashScopeChatModel model = DashScopeChatModel.builder()
                .apiKey(System.getenv("DASHSCOPE_API_KEY"))
                .modelName("qwen-plus")
                .build();

        // Prepare messages
        List<Msg> messages = List.of(
                Msg.builder()
                        .name("user")
                        .role(MsgRole.USER)
                        .content(List.of(TextBlock.builder().text("Hello!").build()))
                        .build()
        );

        // Generate response
        ChatResponse response = model.generate(messages, null).block();
        System.out.println("Response: " + response.getTextContent());
    }
}
```

### Configuration Options

```java
DashScopeChatModel model = DashScopeChatModel.builder()
        .apiKey(System.getenv("DASHSCOPE_API_KEY"))
        .modelName("qwen-plus")                    // Model name
        .baseUrl("https://dashscope.aliyuncs.com") // Optional custom endpoint
        .build();
```

## OpenAI Model

OpenAI models and compatible APIs.

### Basic Usage

```java
import io.agentscope.core.model.OpenAIChatModel;

public class OpenAIExample {
    public static void main(String[] args) {
        // Create model
        OpenAIChatModel model = OpenAIChatModel.builder()
                .apiKey(System.getenv("OPENAI_API_KEY"))
                .modelName("gpt-4o")
                .build();

        // Use the model (same as DashScope)
        ChatResponse response = model.generate(messages, null).block();
    }
}
```

### OpenAI-Compatible APIs

For vLLM, DeepSeek, or other compatible providers:

```java
OpenAIChatModel model = OpenAIChatModel.builder()
        .apiKey("your-api-key")
        .modelName("deepseek-chat")
        .baseUrl("https://api.deepseek.com")  // Custom endpoint
        .build();
```

## Generation Options

Customize model behavior with `GenerateOptions`:

```java
import io.agentscope.core.model.GenerateOptions;

GenerateOptions options = GenerateOptions.builder()
        .temperature(0.7)           // Randomness (0.0-2.0)
        .topP(0.9)                  // Nucleus sampling
        .maxTokens(2000)            // Maximum output tokens
        .frequencyPenalty(0.5)      // Reduce repetition
        .presencePenalty(0.5)       // Encourage diversity
        .build();

// Use with agent
ReActAgent agent = ReActAgent.builder()
        .name("Assistant")
        .model(model)
        .generateOptions(options)
        .build();
```

### Common Parameters

| Parameter         | Type    | Range      | Description                                    |
|-------------------|---------|------------|------------------------------------------------|
| temperature       | Double  | 0.0-2.0    | Controls randomness (higher = more random)     |
| topP              | Double  | 0.0-1.0    | Nucleus sampling threshold                     |
| maxTokens         | Integer | > 0        | Maximum tokens to generate                     |
| frequencyPenalty  | Double  | -2.0-2.0   | Penalizes frequent tokens                      |
| presencePenalty   | Double  | -2.0-2.0   | Penalizes already-present tokens               |
| thinkingBudget    | Integer | > 0        | Token budget for reasoning models              |

## Streaming Responses

Enable streaming for real-time output:

```java
DashScopeChatModel streamingModel = DashScopeChatModel.builder()
        .apiKey(System.getenv("DASHSCOPE_API_KEY"))
        .modelName("qwen-plus")
        .enableStreaming(true)  // Enable streaming
        .build();

// Use with agent for streaming
ReActAgent agent = ReActAgent.builder()
        .name("Assistant")
        .model(streamingModel)
        .build();

// Stream responses
Flux<Event> eventStream = agent.stream(inputMsg);
eventStream.subscribe(event -> {
    if (event.getEventType() == EventType.TEXT_CHUNK) {
        System.out.print(event.getChunk().getText());
    }
});
```

## Vision Models

Use vision models to process images:

```java
import io.agentscope.core.message.ImageBlock;
import io.agentscope.core.message.URLSource;

// Create vision model
DashScopeChatModel visionModel = DashScopeChatModel.builder()
        .apiKey(System.getenv("DASHSCOPE_API_KEY"))
        .modelName("qwen-vl-max")  // Vision model
        .build();

// Prepare multimodal message
Msg imageMsg = Msg.builder()
        .name("user")
        .role(MsgRole.USER)
        .content(List.of(
                TextBlock.builder().text("What's in this image?").build(),
                ImageBlock.builder().source(URLSource.of("https://example.com/image.jpg")).build()
        ))
        .build();

// Generate response
ChatResponse response = visionModel.generate(List.of(imageMsg), null).block();
```

## Reasoning Models

For models that support chain-of-thought reasoning:

```java
DashScopeChatModel reasoningModel = DashScopeChatModel.builder()
        .apiKey(System.getenv("DASHSCOPE_API_KEY"))
        .modelName("qwen-plus")
        .build();

GenerateOptions options = GenerateOptions.builder()
        .thinkingBudget(5000)  // Token budget for thinking
        .build();

ReActAgent agent = ReActAgent.builder()
        .name("Reasoner")
        .model(reasoningModel)
        .generateOptions(options)
        .build();
```

## Timeout and Retry

Configure timeout and retry behavior:

```java
import io.agentscope.core.model.ExecutionConfig;
import java.time.Duration;

ExecutionConfig execConfig = ExecutionConfig.builder()
        .timeout(Duration.ofMinutes(2))          // Request timeout
        .maxRetries(3)                           // Max retry attempts
        .initialBackoff(Duration.ofSeconds(1))   // Initial retry delay
        .maxBackoff(Duration.ofSeconds(10))      // Max retry delay
        .backoffMultiplier(2.0)                  // Exponential backoff
        .build();

GenerateOptions options = GenerateOptions.builder()
        .executionConfig(execConfig)
        .build();

ReActAgent agent = ReActAgent.builder()
        .name("Assistant")
        .model(model)
        .generateOptions(options)
        .build();
```
