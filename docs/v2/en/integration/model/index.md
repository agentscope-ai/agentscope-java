---
title: Model Providers
zh_link: /v2/zh/integration/model/index
---

Model provider extensions connect AgentScope Java to hosted or local chat model APIs. Each provider can be selected through a `ModelRegistry` id when its extension module is on the classpath.

## modelId format and matching rules

`<providerId>:<modelName>` is the recommended canonical form (for example `dashscope:qwen-max`); actual routing follows these matching rules:

| Input form | Match rule | Routes to |
|------------|------------|-----------|
| `<providerId>:<modelName>` (canonical, recommended) | SPI provider pattern | the matching extension module |
| any string (including canonical ids) | model-instance registration, exact match, checked before all other rules | the instance registered via `ModelRegistry.register(modelInstanceId, model)` |
| any string | user factory regex, full-string match (`Pattern#matches`) | `registerFactory` routing |
| `qwen*` bare name (sole legacy exception) | DashScope `qwen.+` | the DashScope module |
| `contextId@modelId` (composite identity) | split on the reserved `@` (`ModelRegistry.resolve`) | the model created with the named creation context |

Provider prefixes follow the module list below (`openai:`, `openai-official:`, `deepseek:`, `glm:`, `kimi:`, `minimax:`, `dashscope:`, `gemini:`, `anthropic:`, `ollama:`). When a model id cannot be resolved, the error message lists the provider ids actually installed on the classpath.

`modelId` here is the registry routing vocabulary. It is distinct from `modelIdentity`, the resolution input of `ModelRegistry.resolve` (composite `contextId@modelId` or bare modelId) — see [ModelRegistry and ModelCreationContext](/v2/en/docs/building-blocks/model#modelregistry-and-modelcreationcontext).

- [OpenAI](/v2/en/integration/model/openai)
- [OpenAI Official](/v2/en/integration/model/openai-official)
- [DeepSeek](/v2/en/integration/model/deepseek)
- [GLM](/v2/en/integration/model/glm)
- [Kimi](/v2/en/integration/model/kimi)
- [MiniMax](/v2/en/integration/model/minimax)
- [DashScope](/v2/en/integration/model/dashscope)
- [Gemini](/v2/en/integration/model/gemini)
- [Anthropic](/v2/en/integration/model/anthropic)
- [Ollama](/v2/en/integration/model/ollama)
