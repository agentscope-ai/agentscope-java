# 模型

本指南介绍 AgentScope Java 支持的 LLM 模型及其配置方法。

## 支持的模型

| 提供商     | 类                      | 流式  | 工具  | 视觉  | 推理  |
|------------|-------------------------|-------|-------|-------|-------|
| DashScope  | `DashScopeChatModel`    | ✅    | ✅    | ✅    | ✅    |
| OpenAI     | `OpenAIChatModel`       | ✅    | ✅    | ✅    | ✅    |
| Anthropic  | `AnthropicChatModel`    | ✅    | ✅    | ✅    | ✅    |
| Gemini     | `GeminiChatModel`       | ✅    | ✅    | ✅    | ✅    |
| Ollama     | `OllamaChatModel`       | ✅    | ✅    | ✅    | ✅    |

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

## ModelRegistry

[`ModelRegistry`](https://github.com/agentscope-ai/agentscope-java/blob/main/agentscope-core/src/main/java/io/agentscope/core/model/ModelRegistry.java)（`io.agentscope.core.model.ModelRegistry`）用**字符串 id**得到 `Model` 实例，适合不想手写各厂商 `*ChatModel.builder()` 的场景。例如 Harness 场景下可使用 `HarnessAgent.builder().model(String)`；任意需要 `Model` 的地方可先调用 `ModelRegistry.resolve(...)` 再传入 `ReActAgent` 等构建器。

### API 一览

| 方法 | 说明 |
|------|------|
| `register(String name, Model model)` | 注册**具名**模型；之后对同名 id 调用 `resolve` 直接返回该实例。 |
| `registerFactory(String regex, ModelFactory factory)` | 为匹配正则的 id 注册自定义工厂；**后注册的工厂优先**于更早注册的用户工厂，且优先于内置规则。 |
| `resolve(String modelId)` | 解析并返回 `Model`；无法解析或创建失败时抛出 `IllegalArgumentException`。 |
| `canResolve(String modelId)` | 仅判断是否可解析（不创建实例）。 |
| `reset()` | 清空具名注册、用户工厂与解析缓存；内置规则保留。一般仅在测试或进程内重置时使用。 |

`ModelFactory` 为函数式接口：`Model create(String modelId)`，参数为完整模型 id 字符串。

### 内置 id 格式与环境变量

在已配置对应环境变量的前提下，可使用下列 id 形式（适用于 `resolve` 以及 `HarnessAgent.Builder.model(String)` 等）：

| id 示例 | 所需环境变量 | 说明 |
|---------|--------------|------|
| `openai:gpt-4o-mini` | `OPENAI_API_KEY` | OpenAI 兼容 HTTP 模型 |
| `dashscope:qwen-max` | `DASHSCOPE_API_KEY` | 阿里云 DashScope / 百炼 |
| `qwen-max` 等以 `qwen-` 开头的 id | `DASHSCOPE_API_KEY` | 将整个字符串作为 DashScope 的 `modelName` |
| `anthropic:claude-sonnet-4-5-20250929` | `ANTHROPIC_API_KEY`（可选；未设置时可依赖 SDK 从环境读取） | Anthropic Claude |
| `gemini:gemini-2.5-flash` | `GEMINI_API_KEY` | Google Gemini API |
| `ollama:llama3` | `OLLAMA_BASE_URL`（可选，默认 `http://localhost:11434`） | 本地 Ollama |

同一进程内，对**相同**工厂解析 id 多次时，返回的 `Model` 实例会被缓存复用；**具名**注册不走该缓存。

### 示例：具名注册（自定义配置后复用）

需要先精细配置（温度、超时等）时，用 Builder 构建一次，再注册成名字：

```java
import io.agentscope.core.model.GenerateOptions;
import io.agentscope.core.model.ModelRegistry;
import io.agentscope.core.model.OpenAIChatModel;
import io.agentscope.harness.agent.HarnessAgent;

Model tuned = OpenAIChatModel.builder()
        .apiKey(System.getenv("OPENAI_API_KEY"))
        .modelName("gpt-4o")
        .generateOptions(GenerateOptions.builder().temperature(0.2).build())
        .build();
ModelRegistry.register("my-gpt4o", tuned);

HarnessAgent agent = HarnessAgent.builder()
        .name("demo")
        .model("my-gpt4o")
        .workspace(workspace)
        .build();
```

### 示例：内置前缀（默认连接参数）

```java
import io.agentscope.harness.agent.HarnessAgent;

HarnessAgent agent = HarnessAgent.builder()
        .name("demo")
        .model("dashscope:qwen-max")
        .workspace(workspace)
        .build();
```

### 示例：自定义工厂

```java
import io.agentscope.core.model.Model;
import io.agentscope.core.model.ModelRegistry;

ModelRegistry.registerFactory(
        "my-llm:.+",
        id -> myModelFactory(id.substring("my-llm:".length())));

Model m = ModelRegistry.resolve("my-llm:prod");
```

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
| `endpointType` | API 端点类型（默认 `AUTO` 自动识别），可选 `TEXT`（强制文本 API）或 `MULTIMODAL`（强制多模态 API） |
| `defaultOptions` | 默认生成选项（temperature、maxTokens 等） |
| `formatter` | 消息格式化器（默认 `DashScopeChatFormatter`） |

### 端点类型（endpointType）

DashScope 模型支持文本和多模态两种 API 端点。默认情况下，框架会根据模型名称自动识别应使用的端点类型（如 `qwen-vl-*` 以及 `qwen3.5` 系列自动使用多模态端点）。

当自动识别不准确时（例如使用自定义模型名称或兼容 API），可以手动指定端点类型：

```java
// 强制使用多模态 API（适用于包含图片/音频等内容的场景）
DashScopeChatModel model = DashScopeChatModel.builder()
        .apiKey(System.getenv("DASHSCOPE_API_KEY"))
        .modelName("custom-model")
        .endpointType(EndpointType.MULTIMODAL)
        .build();

// 强制使用文本 API
DashScopeChatModel model = DashScopeChatModel.builder()
        .apiKey(System.getenv("DASHSCOPE_API_KEY"))
        .modelName("custom-model")
        .endpointType(EndpointType.TEXT)
        .build();
```

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

OllamaChatModel model =
        OllamaChatModel.builder()
                .modelName("qwen3-max")
                .baseUrl("http://localhost:11434")
                .defaultOptions(OllamaOptions.builder()
                        .thinkOption(ThinkOption.ThinkBoolean.ENABLED)
                        .temperature(0.8)
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
| `generateOptions` | 默认生成选项（注意：OpenAI 使用 `.generateOptions()` 而非 `.defaultOptions()`） |

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
        .baseUrl("https://your-gateway.example")  // 可选
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
| `baseUrl` | 自定义 Gemini API 端点（可选） |
| `modelName` | 模型名称，默认 `gemini-2.5-flash` |
| `project` | GCP 项目 ID（Vertex AI） |
| `location` | GCP 区域（Vertex AI） |
| `vertexAI` | 是否使用 Vertex AI |
| `credentials` | GCP 凭证（Vertex AI） |
| `streamEnabled` | 是否启用流式输出，默认 `true` |

如需覆盖请求端点，可使用 `baseUrl(...)`。更高级的传输层或代理配置，仍建议通过 `httpOptions(...)` 或 `clientOptions(...)` 处理。

## Ollama

自托管开源 LLM 平台，支持多种模型。

```java
OllamaChatModel model = OllamaChatModel.builder()
        .modelName("qwen3-max")
        .baseUrl("http://localhost:11434")  // 默认值
        .build();
```

### 配置项

| 配置项 | 说明 |
|--------|------|
| `modelName` | 模型名称，如 `qwen3-max`、`llama3.2`、`mistral`、`phi3` |
| `baseUrl` | Ollama 服务器端点（可选，默认 `http://localhost:11434`） |
| `defaultOptions` | 默认生成选项 |
| `formatter` | 消息格式化器（可选） |
| `httpTransport` | HTTP 传输配置（可选） |

### 高级配置

高级模型加载和生成参数：

```java
OllamaOptions options = OllamaOptions.builder()
        .numCtx(4096)           // 上下文窗口大小
        .temperature(0.7)       // 生成随机性
        .topK(40)               // Top-K 采样
        .topP(0.9)              // 核采样
        .repeatPenalty(1.1)     // 重复惩罚
        .build();
OllamaChatModel model = OllamaChatModel.builder()
        .modelName("qwen3-max")
        .baseUrl("http://localhost:11434")
        .defaultOptions(options)  // 内部转换为 OllamaOptions
        .build();
```

### GenerateOptions 支持

Ollama 也支持 `GenerateOptions` 进行标准配置：

```java
GenerateOptions options = GenerateOptions.builder()
        .temperature(0.7)           // 映射到 Ollama 的 temperature
        .topP(0.9)                  // 映射到 Ollama 的 top_p
        .topK(40)                   // 映射到 Ollama 的 top_k
        .maxTokens(2000)            // 映射到 Ollama 的 num_predict
        .seed(42L)                  // 映射到 Ollama 的 seed
        .frequencyPenalty(0.5)      // 映射到 Ollama 的 frequency_penalty
        .presencePenalty(0.5)       // 映射到 Ollama 的 presence_penalty
        .additionalBodyParam(OllamaOptions.ParamKey.NUM_CTX.getKey(), 4096)      // 上下文窗口大小
        .additionalBodyParam(OllamaOptions.ParamKey.NUM_GPU.getKey(), -1)        // 将所有层卸载到 GPU
        .additionalBodyParam(OllamaOptions.ParamKey.REPEAT_PENALTY.getKey(), 1.1) // 重复惩罚
        .additionalBodyParam(OllamaOptions.ParamKey.MAIN_GPU.getKey(), 0)        // 主 GPU 索引
        .additionalBodyParam(OllamaOptions.ParamKey.LOW_VRAM.getKey(), false)    // 低显存模式
        .additionalBodyParam(OllamaOptions.ParamKey.F16_KV.getKey(), true)       // 16位 KV 缓存
        .additionalBodyParam(OllamaOptions.ParamKey.NUM_THREAD.getKey(), 8)      // CPU 线程数
        .build();

OllamaChatModel model = OllamaChatModel.builder()
        .modelName("qwen3-max")
        .baseUrl("http://localhost:11434")
        .defaultOptions(OllamaOptions.fromGenerateOptions(options))  // 内部转换为 OllamaOptions
        .build();
```

### 可用参数

Ollama 支持超过 40 个参数进行精细调整：

#### 模型加载参数
- `numCtx`: 上下文窗口大小（默认：2048）
- `numBatch`: 提示处理的批处理大小（默认：512）
- `numGPU`: 卸载到 GPU 的层数（-1 表示全部）
- `lowVRAM`: 为有限 GPU 内存启用低显存模式
- `useMMap`: 使用内存映射加载模型
- `useMLock`: 锁定模型在内存中以防止交换

#### 生成参数
- `temperature`: 生成随机性（0.0-2.0）
- `topK`: Top-K 采样（标准：40）
- `topP`: 核采样（标准：0.9）
- `minP`: 最小概率阈值（默认：0.0）
- `numPredict`: 生成的最大 token 数（-1 表示无限）
- `repeatPenalty`: 重复惩罚（默认：1.1）
- `presencePenalty`: 基于 token 存在性的惩罚
- `frequencyPenalty`: 基于 token 频率的惩罚
- `seed`: 可重现结果的随机种子
- `stop`: 立即停止生成的字符串

#### 采样策略
- `mirostat`: Mirostat 采样（0=禁用，1=Mirostat v1，2=Mirostat v2）
- `mirostatTau`: Mirostat 目标熵（默认：5.0）
- `mirostatEta`: Mirostat 学习率（默认：0.1）
- `tfsZ`: 尾部自由采样（默认：1.0 禁用）
- `typicalP`: 典型概率采样（默认：1.0）

## 生成选项

通过 `GenerateOptions` 配置生成参数：

```java
GenerateOptions options = GenerateOptions.builder()
        .temperature(0.7)           // 随机性 (0.0-2.0)
        .topP(0.9)                  // 核采样
        .topK(40)                   // Top-K 采样
        .maxTokens(2000)            // 最大输出 token 数
        .seed(42L)                  // 随机种子
        .toolChoice(new ToolChoice.Auto())  // 工具选择策略
        .build();

DashScopeChatModel model = DashScopeChatModel.builder()
        .apiKey(System.getenv("DASHSCOPE_API_KEY"))
        .modelName("qwen3-max")
        .defaultOptions(options)
        .build();

OllamaChatModel model = OllamaChatModel.builder()
        .modelName("qwen3-max")
        .baseUrl("http://localhost:11434")
        .defaultOptions(OllamaOptions.fromGenerateOptions(options))// 内部转换为 OllamaOptions
        .build();
```

### 参数说明

| 参数 | 类型 | 说明 |
|------|------|------|
| `temperature` | Double | 控制随机性，0.0-2.0 |
| `topP` | Double | 核采样阈值，0.0-1.0 |
| `topK` | Integer | 限制候选 token 数量 |
| `maxTokens` | Integer | 最大生成 token 数 |
| `maxCompletionTokens` | Integer | 最大完成 token 数 |
| `thinkingBudget` | Integer | 思考 token 预算 |
| `reasoningEffort` | String | 推理强度（如 `low`、`medium`、`high`） |
| `frequencyPenalty` | Double | 频率惩罚，-2.0-2.0 |
| `presencePenalty` | Double | 存在惩罚，-2.0-2.0 |
| `seed` | Long | 随机种子 |
| `toolChoice` | ToolChoice | 工具选择策略 |

### 工具选择策略

```java
new ToolChoice.Auto()              // 模型自行决定（默认）
new ToolChoice.None()              // 禁止工具调用
new ToolChoice.Required()          // 强制调用工具
new ToolChoice.Specific("tool_name")  // 强制调用指定工具
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
| Ollama | `OllamaChatFormatter` | `OllamaMultiAgentFormatter` |

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

// Ollama 多智能体
OllamaChatModel model = OllamaChatModel.builder()
        .modelName("qwen3-max")
        .formatter(new OllamaMultiAgentFormatter())
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
