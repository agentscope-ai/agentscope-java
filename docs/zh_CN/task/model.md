# 模型

本指南介绍 AgentScope Java 集成的 LLM 模型 API 及其使用方法。

## 支持的模型

AgentScope Java 目前支持两个主要的 LLM 提供商：

| 提供商     | 类                    | 流式  | 工具  | 视觉  | 推理 |
|------------|-----------------------|-------|-------|-------|-----|
| DashScope  | `DashScopeChatModel`  | ✅    | ✅    | ✅    | ✅  |
| OpenAI     | `OpenAIChatModel`     | ✅    | ✅    | ✅    |    |

> **注意**：`OpenAIChatModel` 兼容 OpenAI 兼容 API，包括 vLLM、DeepSeek 和其他实现 OpenAI API 规范的提供商。

## DashScope 模型

DashScope 是阿里云的 LLM 平台，提供对通义千问系列模型的访问。

### 基本用法

```java
import io.agentscope.core.model.DashScopeChatModel;
import io.agentscope.core.model.ChatResponse;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import io.agentscope.core.message.TextBlock;
import java.util.List;

public class DashScopeExample {
    public static void main(String[] args) {
        // 创建模型
        DashScopeChatModel model = DashScopeChatModel.builder()
                .apiKey(System.getenv("DASHSCOPE_API_KEY"))
                .modelName("qwen-plus")
                .build();

        // 准备消息
        List<Msg> messages = List.of(
                Msg.builder()
                        .name("user")
                        .role(MsgRole.USER)
                        .content(List.of(TextBlock.builder().text("你好！").build()))
                        .build()
        );

        // 生成响应
        ChatResponse response = model.generate(messages, null).block();
        System.out.println("响应: " + response.getTextContent());
    }
}
```

### 配置选项

```java
DashScopeChatModel model = DashScopeChatModel.builder()
        .apiKey(System.getenv("DASHSCOPE_API_KEY"))
        .modelName("qwen-plus")                    // 模型名称
        .baseUrl("https://dashscope.aliyuncs.com") // 可选的自定义端点
        .build();
```

## OpenAI 模型

OpenAI 模型和兼容 API。

### 基本用法

```java
import io.agentscope.core.model.OpenAIChatModel;

public class OpenAIExample {
    public static void main(String[] args) {
        // 创建模型
        OpenAIChatModel model = OpenAIChatModel.builder()
                .apiKey(System.getenv("OPENAI_API_KEY"))
                .modelName("gpt-4o")
                .build();

        // 使用模型（与 DashScope 相同）
        ChatResponse response = model.generate(messages, null).block();
    }
}
```

### OpenAI 兼容 API

对于 vLLM、DeepSeek 或其他兼容提供商：

```java
OpenAIChatModel model = OpenAIChatModel.builder()
        .apiKey("your-api-key")
        .modelName("deepseek-chat")
        .baseUrl("https://api.deepseek.com")  // 自定义端点
        .build();
```

## 生成选项

使用 `GenerateOptions` 自定义模型行为：

```java
import io.agentscope.core.model.GenerateOptions;

GenerateOptions options = GenerateOptions.builder()
        .temperature(0.7)           // 随机性 (0.0-2.0)
        .topP(0.9)                  // 核采样
        .maxTokens(2000)            // 最大输出 token 数
        .frequencyPenalty(0.5)      // 减少重复
        .presencePenalty(0.5)       // 鼓励多样性
        .build();

// 与智能体一起使用
ReActAgent agent = ReActAgent.builder()
        .name("Assistant")
        .model(model)
        .generateOptions(options)
        .build();
```

### 常用参数

| 参数              | 类型    | 范围      | 描述                                         |
|-------------------|---------|-----------|----------------------------------------------|
| temperature       | Double  | 0.0-2.0   | 控制随机性（越高越随机）                      |
| topP              | Double  | 0.0-1.0   | 核采样阈值                                    |
| maxTokens         | Integer | > 0       | 最大生成 token 数                             |
| frequencyPenalty  | Double  | -2.0-2.0  | 惩罚频繁出现的 token                          |
| presencePenalty   | Double  | -2.0-2.0  | 惩罚已出现的 token                            |
| thinkingBudget    | Integer | > 0       | 推理模型的 token 预算                         |

## 流式响应

启用流式以获得实时输出：

```java
DashScopeChatModel streamingModel = DashScopeChatModel.builder()
        .apiKey(System.getenv("DASHSCOPE_API_KEY"))
        .modelName("qwen-plus")
        .enableStreaming(true)  // 启用流式
        .build();

// 与智能体一起使用流式
ReActAgent agent = ReActAgent.builder()
        .name("Assistant")
        .model(streamingModel)
        .build();

// 流式响应
Flux<Event> eventStream = agent.stream(inputMsg);
eventStream.subscribe(event -> {
    if (event.getEventType() == EventType.TEXT_CHUNK) {
        System.out.print(event.getChunk().getText());
    }
});
```

## 视觉模型

使用视觉模型处理图像：

```java
import io.agentscope.core.message.ImageBlock;
import io.agentscope.core.message.URLSource;

// 创建视觉模型
DashScopeChatModel visionModel = DashScopeChatModel.builder()
        .apiKey(System.getenv("DASHSCOPE_API_KEY"))
        .modelName("qwen-vl-max")  // 视觉模型
        .build();

// 准备多模态消息
Msg imageMsg = Msg.builder()
        .name("user")
        .role(MsgRole.USER)
        .content(List.of(
                TextBlock.builder().text("这张图片里有什么？").build(),
                ImageBlock.builder().source(URLSource.of("https://example.com/image.jpg")).build()
        ))
        .build();

// 生成响应
ChatResponse response = visionModel.generate(List.of(imageMsg), null).block();
```

## 推理模型

对于支持思维链推理的模型：

```java
DashScopeChatModel reasoningModel = DashScopeChatModel.builder()
        .apiKey(System.getenv("DASHSCOPE_API_KEY"))
        .modelName("qwen-plus")
        .build();

GenerateOptions options = GenerateOptions.builder()
        .thinkingBudget(5000)  // 思考的 token 预算
        .build();

ReActAgent agent = ReActAgent.builder()
        .name("推理器")
        .model(reasoningModel)
        .generateOptions(options)
        .build();
```

## 超时和重试

配置超时和重试行为：

```java
import io.agentscope.core.model.ExecutionConfig;
import java.time.Duration;

ExecutionConfig execConfig = ExecutionConfig.builder()
        .timeout(Duration.ofMinutes(2))          // 请求超时
        .maxRetries(3)                           // 最大重试次数
        .initialBackoff(Duration.ofSeconds(1))   // 初始重试延迟
        .maxBackoff(Duration.ofSeconds(10))      // 最大重试延迟
        .backoffMultiplier(2.0)                  // 指数退避
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
