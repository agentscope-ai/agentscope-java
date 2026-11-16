---
title: 模型提供商
en_link: /v2/en/integration/model/index
---

模型提供商扩展用于把 AgentScope Java 接入托管或本地 Chat Model API。对应扩展模块在 classpath 中时，可以通过 `ModelRegistry` 字符串 id 选择模型。

## modelId 格式与匹配规则

`<providerId>:<modelName>` 是推荐的规范形式（例如 `dashscope:qwen-max`）；实际路由按以下匹配规则：

| 输入串形态 | 匹配规则 | 出路 |
|------------|----------|------|
| `<providerId>:<modelName>`（规范形式，推荐） | SPI provider 正则 | 对应扩展模块 |
| 任意串（含规范形式 id） | 模型实例注册，精确匹配，优先于其他所有规则 | `ModelRegistry.register(modelInstanceId, model)` 注册的实例 |
| 任意串 | 用户工厂正则全串匹配（`Pattern#matches`） | `registerFactory` 路由 |
| `qwen*` 裸名（唯一 legacy 特例） | DashScope `qwen.+` | DashScope 模块 |
| `contextId@modelId`（组合身份） | 按保留字符 `@` 拆分（`ModelRegistry.resolve`） | 用命名创建上下文创建的模型 |

provider 前缀与下方模块列表对应（`openai:`、`openai-official:`、`deepseek:`、`glm:`、`kimi:`、`minimax:`、`dashscope:`、`gemini:`、`anthropic:`、`ollama:`）。model id 无法解析时，错误信息会列出 classpath 上实际已安装的 provider id。

本节的 `modelId` 是注册表路由层的词汇，区别于 `modelIdentity`——后者是 `ModelRegistry.resolve` 的解析入参（组合形式 `contextId@modelId` 或裸 modelId），参见 [ModelRegistry 与 ModelCreationContext](/v2/zh/docs/building-blocks/model#modelregistry-与-modelcreationcontext)。

- [OpenAI](/v2/zh/integration/model/openai)
- [OpenAI Official](/v2/zh/integration/model/openai-official)
- [DeepSeek](/v2/zh/integration/model/deepseek)
- [GLM](/v2/zh/integration/model/glm)
- [Kimi](/v2/zh/integration/model/kimi)
- [MiniMax](/v2/zh/integration/model/minimax)
- [DashScope](/v2/zh/integration/model/dashscope)
- [Gemini](/v2/zh/integration/model/gemini)
- [Anthropic](/v2/zh/integration/model/anthropic)
- [Ollama](/v2/zh/integration/model/ollama)
