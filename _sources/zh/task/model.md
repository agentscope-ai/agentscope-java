# 模型

本指南介绍 AgentScope Java 支持的 LLM 模型及其配置方法。

## 支持的模型

| 提供商     | 类                      | 流式  | 工具  | 视觉  | 推理  |
|------------|-------------------------|-------|-------|-------|-------|
| DashScope  | `DashScopeChatModel`    | ✅    | ✅    | ✅    | ✅    |
| OpenAI     | `OpenAIChatModel`       | ✅    | ✅    | ✅    |       |
| Anthropic  | `AnthropicChatModel`    | ✅    | ✅    | ✅    | ✅    |
| Gemini     | `GeminiChatModel`       | ✅    | ✅    | ✅    | ✅    |

> **注意**：
> - `OpenAIChatModel` 兼容 OpenAI API 规范，可用于 vLLM、DeepSeek 等提供商
> - `GeminiChatModel` 同时支持 Gemini API 和 Vertex AI

## 获取 API Key

| 提供商 | 获取地址 | 环境变量 |
|--------|----------|----------|
| DashScope | [阿里云百炼控制台](https://bailian.console.aliyun.com/) | `DASHSCOPE_API_KEY` |
| OpenAI | [OpenAI Platform](https://platform.openai.com/api-keys) | `OPENAI_API_KEY` |
| Anthropic | [Anthropic Console](https://console.anthropic.com/settings/keys) | `ANTHROPIC_API_KEY` |
| Gemini | [Google AI Studio](https://aistudio.google.com/apikey) | `GEMINI_API_KEY` |
| DeepSeek | [DeepSeek 开放平台](https://platform.deepseek.com/api_keys) | - |

## DashScope

阿里云 LLM 平台，提供通义千问系列模型。

```java
DashScopeChatModel model = DashScopeChatModel.builder()
        .apiKey(System.getenv("DASHSCOPE_API_KEY"))
        .modelName("qwen3-max")
        .build();
```

### 配置项

| 配置项 | 说明 |
|--------|------|
| `apiKey` | DashScope API 密钥 |
| `modelName` | 模型名称，如 `qwen3-max`、`qwen-vl-max` |
| `baseUrl` | 自定义 API 端点（可选） |
| `stream` | 是否启用流式输出，默认 `true` |
| `enableThinking` | 启用思考模式，模型会展示推理过程 |
| `enableSearch` | 启用联网搜索，获取实时信息 |

### 思考模式

```java
DashScopeChatModel model = DashScopeChatModel.builder()
        .apiKey(System.getenv("DASHSCOPE_API_KEY"))
        .modelName("qwen3-max")
        .enableThinking(true)  // 自动启用流式输出
        .defaultOptions(GenerateOptions.builder()
                .thinkingBudget(5000)  // 思考 token 预算
                .build())
        .build();
```

## OpenAI

OpenAI 模型及兼容 API。

```java
OpenAIChatModel model = OpenAIChatModel.builder()
        .apiKey(System.getenv("OPENAI_API_KEY"))
        .modelName("gpt-4o")
        .build();
```

### 兼容 API

适用于 DeepSeek、vLLM 等兼容提供商：

```java
OpenAIChatModel model = OpenAIChatModel.builder()
        .apiKey("your-api-key")
        .modelName("deepseek-chat")
        .baseUrl("https://api.deepseek.com")
        .build();
```

### 配置项

| 配置项 | 说明 |
|--------|------|
| `apiKey` | API 密钥 |
| `modelName` | 模型名称，如 `gpt-4o`、`gpt-4o-mini` |
| `baseUrl` | 自定义 API 端点（可选） |
| `stream` | 是否启用流式输出，默认 `true` |

## Anthropic

Anthropic 的 Claude 系列模型。

```java
AnthropicChatModel model = AnthropicChatModel.builder()
        .apiKey(System.getenv("ANTHROPIC_API_KEY"))
        .modelName("claude-sonnet-4-5-20250929")  // 默认值
        .build();
```

### 配置项

| 配置项 | 说明 |
|--------|------|
| `apiKey` | Anthropic API 密钥 |
| `modelName` | 模型名称，默认 `claude-sonnet-4-5-20250929` |
| `baseUrl` | 自定义 API 端点（可选） |
| `stream` | 是否启用流式输出，默认 `true` |

## Gemini

Google 的 Gemini 系列模型，支持 Gemini API 和 Vertex AI。

### Gemini API

```java
GeminiChatModel model = GeminiChatModel.builder()
        .apiKey(System.getenv("GEMINI_API_KEY"))
        .modelName("gemini-2.5-flash")  // 默认值
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

### 配置项

| 配置项 | 说明 |
|--------|------|
| `apiKey` | Gemini API 密钥 |
| `modelName` | 模型名称，默认 `gemini-2.5-flash` |
| `project` | GCP 项目 ID（Vertex AI） |
| `location` | GCP 区域（Vertex AI） |
| `vertexAI` | 是否使用 Vertex AI |
| `credentials` | GCP 凭证（Vertex AI） |
| `streamEnabled` | 是否启用流式输出，默认 `true` |

## 生成选项

通过 `GenerateOptions` 配置生成参数：

```java
GenerateOptions options = GenerateOptions.builder()
        .temperature(0.7)           // 随机性 (0.0-2.0)
        .topP(0.9)                  // 核采样
        .topK(40)                   // Top-K 采样
        .maxTokens(2000)            // 最大输出 token 数
        .seed(42L)                  // 随机种子
        .toolChoice(new ToolChoice.auto())  // 工具选择策略
        .build();

DashScopeChatModel model = DashScopeChatModel.builder()
        .apiKey(System.getenv("DASHSCOPE_API_KEY"))
        .modelName("qwen3-max")
        .defaultOptions(options)
        .build();
```

### 参数说明

| 参数 | 类型 | 说明 |
|------|------|------|
| `temperature` | Double | 控制随机性，0.0-2.0 |
| `topP` | Double | 核采样阈值，0.0-1.0 |
| `topK` | Integer | 限制候选 token 数量 |
| `maxTokens` | Integer | 最大生成 token 数 |
| `thinkingBudget` | Integer | 思考 token 预算 |
| `seed` | Long | 随机种子 |
| `toolChoice` | ToolChoice | 工具选择策略 |

### 工具选择策略

```java
ToolChoice.auto()              // 模型自行决定（默认）
ToolChoice.none()              // 禁止工具调用
ToolChoice.required()          // 强制调用工具
ToolChoice.specific("tool_name")  // 强制调用指定工具
```

### 扩展参数

支持传递提供商特有的参数：

```java
GenerateOptions options = GenerateOptions.builder()
        .additionalHeader("X-Custom-Header", "value")
        .additionalBodyParam("custom_param", "value")
        .additionalQueryParam("version", "v2")
        .build();
```

## 超时和重试

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

Formatter 负责将 AgentScope 的统一消息格式转换为各 LLM 提供商的 API 格式。每个提供商有两种 Formatter：

| 提供商 | 单智能体 | 多智能体 |
|--------|----------|----------|
| DashScope | `DashScopeChatFormatter` | `DashScopeMultiAgentFormatter` |
| OpenAI | `OpenAIChatFormatter` | `OpenAIMultiAgentFormatter` |
| Anthropic | `AnthropicChatFormatter` | `AnthropicMultiAgentFormatter` |
| Gemini | `GeminiChatFormatter` | `GeminiMultiAgentFormatter` |

### 默认行为

不指定 Formatter 时，模型使用对应的 `ChatFormatter`，适用于单智能体场景。

### 多智能体场景

在多智能体协作（如 Pipeline、MsgHub）中，需要使用 `MultiAgentFormatter`。它会：

- 将多个智能体的消息合并为对话历史
- 使用 `<history></history>` 标签结构化历史消息
- 区分当前智能体和其他智能体的发言

```java
// DashScope 多智能体
DashScopeChatModel model = DashScopeChatModel.builder()
        .apiKey(System.getenv("DASHSCOPE_API_KEY"))
        .modelName("qwen3-max")
        .formatter(new DashScopeMultiAgentFormatter())
        .build();

// OpenAI 多智能体
OpenAIChatModel model = OpenAIChatModel.builder()
        .apiKey(System.getenv("OPENAI_API_KEY"))
        .modelName("gpt-4o")
        .formatter(new OpenAIMultiAgentFormatter())
        .build();

// Anthropic 多智能体
AnthropicChatModel model = AnthropicChatModel.builder()
        .apiKey(System.getenv("ANTHROPIC_API_KEY"))
        .formatter(new AnthropicMultiAgentFormatter())
        .build();

// Gemini 多智能体
GeminiChatModel model = GeminiChatModel.builder()
        .apiKey(System.getenv("GEMINI_API_KEY"))
        .formatter(new GeminiMultiAgentFormatter())
        .build();
```

### 自定义历史提示

可以自定义对话历史的提示语：

```java
String customPrompt = "# 对话记录\n以下是之前的对话内容：\n";

DashScopeChatModel model = DashScopeChatModel.builder()
        .apiKey(System.getenv("DASHSCOPE_API_KEY"))
        .modelName("qwen3-max")
        .formatter(new DashScopeMultiAgentFormatter(customPrompt))
        .build();
```

### 何时使用 MultiAgentFormatter

| 场景 | 推荐 Formatter |
|------|----------------|
| 单智能体对话 | `ChatFormatter`（默认） |
| Pipeline 顺序执行 | `MultiAgentFormatter` |
| MsgHub 群聊 | `MultiAgentFormatter` |
| 多智能体辩论 | `MultiAgentFormatter` |
