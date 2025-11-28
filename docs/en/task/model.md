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
import io.agentscope.core.message.*;
import io.agentscope.core.model.DashScopeChatModel;
import reactor.core.publisher.Mono;
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
        //Use model
        model.stream(messages, null, null).flatMapIterable(ChatResponse::getContent)
                .map(block -> {
                    if (block instanceof TextBlock tb) return tb.getText();
                    if (block instanceof ThinkingBlock tb) return tb.getThinking();
                    if (block instanceof ToolUseBlock tub) return tub.getContent();
                    return "";
                }).filter(text -> !text.isEmpty())
                .doOnNext(System.out::print)
                .blockLast();
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
import io.agentscope.core.message.*;
import io.agentscope.core.model.OpenAIChatModel;
import reactor.core.publisher.Mono;
import java.util.List;

public class OpenAIExample {
    public static void main(String[] args) {
        // Create model
        OpenAIChatModel model = OpenAIChatModel.builder()
                .apiKey(System.getenv("OPENAI_API_KEY"))
                .modelName("gpt-4o")
                .build();

        // Prepare messages
        List<Msg> messages = List.of(
                Msg.builder()
                        .name("user")
                        .role(MsgRole.USER)
                        .content(List.of(TextBlock.builder().text("Hello!").build()))
                        .build()
        );
        // Use the model (same as DashScope)
        model.stream(messages, null, null).flatMapIterable(ChatResponse::getContent)
                .map(block -> {
                    if (block instanceof TextBlock tb) return tb.getText();
                    if (block instanceof ThinkingBlock tb) return tb.getThinking();
                    if (block instanceof ToolUseBlock tub) return tub.getContent();
                    return "";
                }).filter(text -> !text.isEmpty())
                .doOnNext(System.out::print)
                .blockLast();
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

// Use with model
DashScopeChatModel model = DashScopeChatModel.builder()
        .apiKey(System.getenv("DASHSCOPE_API_KEY"))
        .modelName("qwen-plus")
        .defaultOptions(options)
        .build();
```

### Common Parameters

| Parameter         | Type    | Range    | Description                                    |
|-------------------|---------|----------|------------------------------------------------|
| temperature       | Double  | 0.0-2.0  | Controls randomness (higher = more random)     |
| topP              | Double  | 0.0-1.0  | Nucleus sampling threshold                     |
| maxTokens         | Integer | \> 0     | Maximum tokens to generate                     |
| frequencyPenalty  | Double  | -2.0-2.0 | Penalizes frequent tokens                      |
| presencePenalty   | Double  | -2.0-2.0 | Penalizes already-present tokens               |
| thinkingBudget    | Integer | \> 0     | Token budget for reasoning models              |

## Use with Agent

```java
DashScopeChatModel streamingModel = DashScopeChatModel.builder()
        .apiKey(System.getenv("DASHSCOPE_API_KEY"))
        .modelName("qwen-plus")
        .stream(true)  // Enable streaming
        .build();

// Use with agent
ReActAgent agent = ReActAgent.builder()
        .name("Assistant")
        .model(streamingModel)
        .build();

// Prepare messages
List<Msg> messages = List.of(
        Msg.builder()
                .name("user")
                .role(MsgRole.USER)
                .content(List.of(TextBlock.builder().text("Hello!").build()))
                .build()
);

// Stream responses
Flux<Event> eventStream = agent.stream(messages);
eventStream.subscribe(event -> {
        if (!event.isLast()) System.out.print(event.getMessage().getTextContent());
});
```

## Vision Models

Use vision models to process images:

```java
import io.agentscope.core.message.*;
import io.agentscope.core.model.ChatResponse;
import io.agentscope.core.model.DashScopeChatModel;
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
                ImageBlock.builder().source(URLSource.builder().url("https://example.com/image.jpg").build()).build()
        ))
        .build();

// Generate response
visionModel.stream(List.of(imageMsg), null, null)
        .flatMapIterable(ChatResponse::getContent)
        .map(block -> {
            if (block instanceof TextBlock tb) return tb.getText();
            if (block instanceof ThinkingBlock tb) return tb.getThinking();
            if (block instanceof ToolUseBlock tub) return tub.getContent();
            return "";
        }).filter(text -> !text.isEmpty())
        .doOnNext(System.out::print)
        .blockLast();
```

## Reasoning Models

For models that support chain-of-thought reasoning:

```java
GenerateOptions options = GenerateOptions.builder()
        .thinkingBudget(5000)  // Token budget for thinking
        .build();

DashScopeChatModel reasoningModel = DashScopeChatModel.builder()
        .apiKey(System.getenv("DASHSCOPE_API_KEY"))
        .modelName("qwen-plus")
        .defaultOptions(options)
        .build();

ReActAgent agent = ReActAgent.builder()
        .name("Reasoner")
        .model(reasoningModel)
        .build();
```

## Timeout and Retry

Configure timeout and retry behavior:

```java
import io.agentscope.core.ReActAgent;
import java.time.Duration;
import io.agentscope.core.model.DashScopeChatModel;
import io.agentscope.core.model.ExecutionConfig;
import io.agentscope.core.model.GenerateOptions;

ExecutionConfig execConfig = ExecutionConfig.builder()
        .timeout(Duration.ofMinutes(2))          // Request timeout
        .maxAttempts(3)                          // Max retry attempts
        .initialBackoff(Duration.ofSeconds(1))   // Initial retry delay
        .maxBackoff(Duration.ofSeconds(10))      // Max retry delay
        .backoffMultiplier(2.0)                  // Exponential backoff
        .build();

GenerateOptions options = GenerateOptions.builder()
        .executionConfig(execConfig)
        .build();

DashScopeChatModel model = DashScopeChatModel.builder()
        .apiKey(System.getenv("DASHSCOPE_API_KEY"))
        .modelName("qwen-plus")
        .defaultOptions(options)
        .build();

ReActAgent agent = ReActAgent.builder()
        .name("Assistant")
        .model(model)
        .build();
```
