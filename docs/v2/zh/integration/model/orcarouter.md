# OrcaRouter 模型

`agentscope-extensions-model-openai` 通过 OpenAI 兼容模型栈提供 [OrcaRouter](https://www.orcarouter.ai) 的一等支持。引入 OpenAI 模型扩展模块后，可以通过 `ModelRegistry` 使用 `orcarouter:<model>`。

OrcaRouter 是一个 OpenAI 兼容的 AI 网关，像 OpenRouter 一样在同一端点暴露 provider/model 命名空间，覆盖众多模型；同时还把自适应路由、自动故障转移、零加价推理、可观测性、护栏和 agent 工具治理整合在同一个端点后面。该端点还内置了网关级、零信任的 AI Agent 安全能力——对每次 prompt/response 进行筛查，并以默认拒绝的方式治理每一次工具调用，无需任何应用代码改动。

## 添加依赖

```xml
<dependency>
    <groupId>io.agentscope</groupId>
    <artifactId>agentscope-extensions-model-openai</artifactId>
    <version>${agentscope.version}</version>
</dependency>
```

## ModelRegistry

设置 `ORCAROUTER_API_KEY` 后，使用 `orcarouter:<model>` 字符串 id：

```java
ReActAgent agent = ReActAgent.builder()
    .name("assistant")
    .model("orcarouter:openai/gpt-5.5") // 底层由 ModelRegistry.resolve(modelId) 解析
    .build();
```

Provider 默认使用 `https://api.orcarouter.ai/v1`，发送请求前会去掉 `orcarouter:` 前缀，并使用 `io.agentscope.extensions.model.openai.formatter` 下的标准 OpenAI chat formatter。模型名采用 `provider/model` 命名空间，例如 `openai/gpt-5.5`、`anthropic/claude-opus-4.8`、`deepseek/deepseek-v4-pro`，或使用 `orcarouter/auto` 自适应路由。完整模型列表见 <https://www.orcarouter.ai/models>。

## 兼容性说明

由于 OrcaRouter 会路由到一批上游 provider，其 `response_format` 支持程度不一，因此 native structured output 默认关闭，agent 回退到 AgentScope 标准的 `generate_response` 工具。如果你配置的路线可靠支持 `json_schema`，可以通过 `ModelCreationContext` 传入 `nativeStructuredOutput` 或 formatter 覆盖。

使用自建或自定义网关时，可以通过 `ModelCreationContext` 传入 `baseUrl`、`endpointPath`、生成参数或 formatter 覆盖。
