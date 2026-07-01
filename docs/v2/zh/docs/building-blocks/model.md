---
title: "Model"
description: "在 AgentScope Java 中配置并连接 LLM 模型提供商"
---

## 概述

模型层采用两层结构：上层是 **Credential**（`io.agentscope.core.credential`），承载某个提供商的 API 鉴权字段；下层是 **Chat Model**，即在该凭证基础上对接的具体推理模型实现。

```text
CredentialBase/
└── ChatModelBase/
    ├── OpenAIChatModel
    ├── AnthropicChatModel
    ├── DashScopeChatModel
    ├── GeminiChatModel
    └── OllamaChatModel
```

**Credential** 承载某个提供商的 API 认证字段（`apiKey`、`baseUrl` 等）。从一个凭证出发，可以通过 `listModels()` 获取该提供商支持的模型列表（`List<ModelCard>`）。

这种分层与前端的自然交互流程一致 —— 先注册凭证，再从凭证下挑选模型 —— 让界面只需鉴权一次，就能展示该提供商支持的所有模型。

## Provider 模块迁移

Provider-specific 的模型实现已经从 `agentscope-core` 迁移到独立 extension module 中。core 现在只保留共享模型契约与运行时工具；每个 provider module 自己维护 chat model、credential、formatter、DTO、异常、SDK/API client 代码。

| Provider | Maven artifact | 主要包名 |
|----------|----------------|----------|
| OpenAI | `agentscope-extensions-model-openai` | `io.agentscope.extensions.model.openai` |
| DashScope | `agentscope-extensions-model-dashscope` | `io.agentscope.extensions.model.dashscope` |
| Gemini | `agentscope-extensions-model-gemini` | `io.agentscope.extensions.model.gemini` |
| Anthropic | `agentscope-extensions-model-anthropic` | `io.agentscope.extensions.model.anthropic` |
| Ollama | `agentscope-extensions-model-ollama` | `io.agentscope.extensions.model.ollama` |

### 迁移步骤

1. 增加对应 provider extension module 依赖。以 DashScope 为例：

```xml
<dependency>
    <groupId>io.agentscope</groupId>
    <artifactId>agentscope-extensions-model-dashscope</artifactId>
</dependency>
```

其他 provider artifact 遵循同样模式：`agentscope-extensions-model-openai`、`agentscope-extensions-model-gemini`、`agentscope-extensions-model-anthropic`、`agentscope-extensions-model-ollama`。

2. 将 provider 模型 import 从 `io.agentscope.core.model.*` 改为 `io.agentscope.extensions.model.<provider>.*`。
3. 将 provider formatter import 从 `io.agentscope.core.formatter.<provider>.*` 改为 `io.agentscope.extensions.model.<provider>.formatter.*`。
4. Spring Boot 应用中，改用对应 provider-specific starter 和 `agentscope.<provider>.*` 配置：

```xml
<dependency>
    <groupId>io.agentscope</groupId>
    <artifactId>agentscope-openai-spring-boot-starter</artifactId>
</dependency>
```

### 非 Spring 应用

直接使用 provider 类时，需要引入对应 extension 依赖，并更新 import。例如：

```java
import io.agentscope.extensions.model.dashscope.DashScopeChatModel;
import io.agentscope.extensions.model.dashscope.formatter.DashScopeChatFormatter;
```

然后通过 provider builder 创建模型，并把 `Model` 实例传给 agent：

```java
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

### Spring Boot 应用

Spring Boot 场景下，优先使用 provider-specific starter，例如 `agentscope-openai-spring-boot-starter`、`agentscope-dashscope-spring-boot-starter`、`agentscope-gemini-spring-boot-starter`、`agentscope-anthropic-spring-boot-starter`。这些 starter 直接依赖对应 model extension，创建 Spring 管理的 `Model` bean，通用的 `agentscope-spring-boot-starter` 继续负责 AgentScope 的公共基础设施。

OpenAI 示例：

```yaml
agentscope:
  model:
    provider: openai
  openai:
    api-key: ${OPENAI_API_KEY}
    model-name: gpt-4.1-mini
    stream: true
```

## Chat Model

**Chat Model** 是驱动 agent 对话与工具调用的 LLM，输入输出可以是文本之外的多模态内容。AgentScope Java 当前提供以下 Chat Model 类：

| 提供商 | 模型类 | 说明 |
|--------|--------|------|
| OpenAI | `OpenAIChatModel` | Chat Completions API，兼容 vLLM 与 OpenAI 兼容端点（含 DeepSeek、Kimi 等） |
| Anthropic | `AnthropicChatModel` | Claude 模型，支持 prompt 缓存与 thinking |
| DashScope | `DashScopeChatModel` | Qwen 模型，多模态（视觉/音频/视频）、推理 |
| Gemini | `GeminiChatModel` | Google Gemini 模型，支持多模态 |
| Ollama | `OllamaChatModel` | 本地 LLM 托管，凭证可选 |

凭证类：`OpenAICredential`、`AnthropicCredential`、`DashScopeCredential`、`GeminiCredential`、`OllamaCredential`、`DeepSeekCredential`、`KimiCredential`、`XAICredential`。

### 创建 Chat Model

每个 Chat Model 通过 builder 构造，最常见的字段是 `apiKey`、`modelName`、`stream`、`formatter`、`defaultOptions`。下面三个 tab 分别展示流式、工具调用与推理三种典型初始化场景：

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

各 Chat Model 的 builder 共享的字段大致相同：

| 字段 | 类型 | 说明 |
|------|------|------|
| `apiKey` | `String` | API key（部分 provider 也支持 `credential(...)` 方式注入） |
| `modelName` | `String` | 模型标识符（如 `"qwen-plus"`） |
| `stream` | `boolean` | 是否流式输出 |
| `defaultOptions` | `GenerateOptions` | 提供商专属生成参数（`temperature`、`maxTokens`、`thinkingBudget`、`parallelToolCalls` 等） |
| `formatter` | `Formatter` | 覆盖默认的消息 formatter |
| `baseUrl` | `String` | 自定义服务端点（如 OpenAI 兼容的反代） |

### 调用 Chat Model

`Model` 接口暴露统一的 `stream(messages, tools, options)`，返回 `Flux<ChatResponse>`：

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

`ChatResponse` 包含若干 content block（`TextBlock`、`ThinkingBlock`、`ToolUseBlock`、`DataBlock`）以及记录 token 数与耗时的 `ChatUsage`。

实际开发中通常不需要直接调模型，而是通过 `ReActAgent` 调度；要直连模型做轻量调用时，推荐参考 `agentscope-examples/documentation/.../model/ModelRegistryExample.java`。

### 生成结构化输出

Agent 层提供把模型输出绑定到 Java POJO 的便捷重载，由 `ReActAgent.call(msgs, structuredOutputClass, runtimeContext)` 暴露：

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

实现细节：框架会基于目标 Class 合成强制结构化的工具调用，再校验并修复模型输出，最后把结果挂到 `Msg.metadata` 的 `structured_output` 字段，供 `getStructuredData(Class)` 直接反序列化。完整示例：`agentscope-examples/documentation/.../structuredoutput/StructuredOutputExample.java`。

#### 结构化输出路径选择

框架提供两条结构化输出路径：

| 路径 | 条件 | 机制 |
|------|------|------|
| **Native** | `supportsNativeStructuredOutput() = true` | 通过 `response_format` + `json_schema` 让模型直接输出合规 JSON |
| **Fallback**（默认） | `supportsNativeStructuredOutput() = false` | 注入 `generate_response` 合成工具，模型通过 tool call 返回结构化数据 |

当 native 路径失败（如模型返回 400），框架会**自动降级**到 fallback 路径，无需用户干预。

#### 各 Provider 默认行为

| Provider | `supportsNativeStructuredOutput` | 说明 |
|----------|----------------------------------|------|
| OpenAI (GPT-4o 等) | `true` | 原生支持 `json_schema` |
| OpenAI (DeepSeek/GLM formatter) | `false` | 不支持，自动走 fallback |
| DashScope | `false` | DashScope 原生端点仅支持 `json_object`，不支持 `json_schema`；框架默认走 fallback |
| Anthropic | `false`（默认） | — |

> **DashScope 用户注意**：DashScope 的思考模式（`enableThinking(true)`）不支持结构化输出，框架会强制走 fallback 路径。

#### 显式配置

如果确认你的模型/端点支持 `json_schema`，可以通过 builder 开启 native 路径：

```java
DashScopeChatModel model = DashScopeChatModel.builder()
        .apiKey(System.getenv("DASHSCOPE_API_KEY"))
        .modelName("qwen-plus")
        .nativeStructuredOutput(true)  // 显式开启 native json_schema 路径
        .build();
```

#### 结构化输出与工具调用共存

当 Agent 同时注册了工具并请求结构化输出时，部分 OpenAI 兼容 API（如 Kimi、Deepseek 等）会优先遵循 `response_format` 约束而跳过工具调用。设置 `nativeStructuredOutputWithTools(false)` 可解决此问题：

```java
OpenAIChatModel model = OpenAIChatModel.builder()
        .apiKey("...")
        .baseUrl("https://api.moonshot.cn/v1")
        .modelName("moonshot-v1-8k")
        .nativeStructuredOutputWithTools(false)
        .build();
```

`DashScopeChatModel` 同样支持此配置。对于 OpenAI 原生模型（GPT-4o 等）无需设置。

### Formatter

**Formatter** 负责把 AgentScope 的 `Msg` 对象转换为各提供商 API 期望的请求载荷。它通过 Chat Model builder 的 `formatter(...)` 字段配置。每个提供商内置两种 formatter：

| 类型 | 适用场景 |
|------|----------|
| **ChatFormatter**（默认） | 标准的单 agent 对话。每条 `Msg` 1:1 映射为一条 API 消息，保留原始角色（`USER`、`ASSISTANT`、`SYSTEM`）。 |
| **MultiAgentFormatter** | 多 agent 场景，例如辩论、moderator。连续的 agent 消息会被聚合，并标注发送者名字。 |

切换到多 agent 模式只需传入 MultiAgent 变体，无需修改 agent 代码：

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

各 provider 的 formatter 类现在随 provider extension module 一起提供：

| Provider | Chat | MultiAgent |
|---|---|---|
| DashScope | `DashScopeChatFormatter` | `DashScopeMultiAgentFormatter` |
| OpenAI | `OpenAIChatFormatter` | `OpenAIMultiAgentFormatter` |
| Anthropic | `AnthropicChatFormatter` | `AnthropicMultiAgentFormatter` |
| Gemini | `GeminiChatFormatter` | `GeminiMultiAgentFormatter` |
| Ollama | `OllamaChatFormatter` | `OllamaMultiAgentFormatter` |

如果提供商的载荷格式不属于以上几种，开发者可以实现 `Formatter<TReq, TResp, TParams>` 接口（位于 `io.agentscope.core.formatter`），并通过同一个 `formatter(...)` 字段传入。

### 自定义 Provider

接入自定义 provider 的最小路径是：实现一个 `CredentialBase` 子类与一个 `ChatModelBase` 子类。

#### 步骤 1：定义 Credential

继承 `CredentialBase`，实现 `getChatModelClass()`：

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

#### 步骤 2：实现 Chat Model

继承 `ChatModelBase`，实现 `doStream`：

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
        // 调用提供商 API、把响应封装为 ChatResponse 流
        return Flux.empty();
    }
}
```

#### 步骤 3：注册到 ModelRegistry（可选）

`ModelRegistry` 可以让 `ReActAgent.builder().model("provider:model-name")` 字符串化解析模型：

```java
import io.agentscope.core.model.ModelRegistry;

ModelRegistry.registerFactory(
        "myprov:.*",
        modelId -> new MyProviderChatModel(
                new MyProviderCredential(System.getenv("MYPROV_API_KEY"), null),
                modelId.substring("myprov:".length())));

// 之后即可：
// ReActAgent.builder().model("myprov:my-model-v1")...
```

## 前端集成

### 什么是 ModelCard

`ModelCard`（`credential/ModelCard.java`）是对模型能力与约束的声明式描述，用于驱动前端 —— 模型选择器、参数表单、能力开关都可以基于它动态渲染，无需在前端硬编码任何提供商相关的逻辑。

当前 `ModelCard` 是一个最小化的 record，包含：

| 字段 | 类型 | 说明 |
|------|------|------|
| `modelName()` | `String` | 模型标识符（例如 `"claude-sonnet-4-6"`） |
| `displayName()` | `String` | 用于展示的可读名称（例如 `"Claude Sonnet 4.6"`） |
| `contextSize()` | `Integer` | 最大上下文窗口（token 数） |

:::{note}
ModelCard 字段当前最小化；能力标记（输入/输出 MIME 类型）与参数 schema 将随模型发现基础设施完善而扩展。
:::

### 获取 ModelCard

通过 `CredentialBase#listModels()` 获取 Model Card，返回 `Mono<List<ModelCard>>`：

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

`getChatModelClass()` 返回对应的 `ChatModelBase` 子类，可用于反向构造默认 model：

```java
Class<? extends io.agentscope.core.model.ChatModelBase> modelCls = cred.getChatModelClass();
```

这种设计让前端只需一个 credential，就能发现该 provider 下的可用模型 —— 无需任何硬编码的提供商逻辑。
