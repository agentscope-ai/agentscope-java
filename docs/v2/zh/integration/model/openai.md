# OpenAI 模型

`agentscope-extensions-model-openai` 接入 OpenAI Chat Completions 风格的模型。OpenAI 兼容端点也使用这个适配模块，例如 DeepSeek、GLM 等遵循 OpenAI API 载荷格式的服务。

## 添加依赖

```xml
<dependency>
    <groupId>io.agentscope</groupId>
    <artifactId>agentscope-extensions-model-openai</artifactId>
    <version>${agentscope.version}</version>
</dependency>
```

## ModelRegistry

设置 `OPENAI_API_KEY` 后，使用 `openai:<model>` 字符串 id：

```java
ReActAgent agent = ReActAgent.builder()
    .name("assistant")
    .model("openai:gpt-4.1-mini") // 底层由 ModelRegistry.resolve(modelId) 解析
    .build();
```

## 显式 builder

需要自定义 endpoint、formatter、transport 或生成参数时，使用 builder：

```java
import io.agentscope.extensions.model.openai.OpenAIChatModel;

OpenAIChatModel model = OpenAIChatModel.builder()
    .apiKey(System.getenv("OPENAI_API_KEY"))
    .modelName("gpt-4.1-mini")
    .stream(true)
    .build();
```

接入兼容端点时，设置 `baseUrl(...)` 和该服务期望的模型名。

## GLM（智谱 AI）

GLM 在 `io.agentscope.extensions.model.openai.compat.glm` 包中提供了专用适配。它底层复用 `OpenAIChatModel`，但默认配置为智谱开放平台端点（`https://open.bigmodel.cn/api/paas/v4`）。

设置 `GLM_API_KEY`（或 `ZHIPUAI_API_KEY`）后，使用 `glm:<model>` 字符串 id：

```java
ReActAgent agent = ReActAgent.builder()
    .name("assistant")
    .model("glm:glm-5.2") // 底层由 GLMModelProvider 通过 ModelRegistry 解析
    .build();
```

也可以显式构建并指定 GLM formatter：

```java
import io.agentscope.extensions.model.openai.OpenAIChatModel;
import io.agentscope.extensions.model.openai.compat.glm.GLMFormatter;

OpenAIChatModel model = OpenAIChatModel.builder()
    .apiKey(System.getenv("GLM_API_KEY"))
    .baseUrl("https://open.bigmodel.cn/api/paas/v4")
    .modelName("glm-5.2")
    .formatter(new GLMFormatter()) // 多智能体提示词场景使用 GLMMultiAgentFormatter
    .stream(true)
    .build();
```

GLM formatter 按最新智谱 API 对请求做了以下适配：

- 保证至少存在一条 user 消息（否则 API 返回错误码 1214）。
- `tool_choice` 仅支持 `auto`，其他取值会降级为 `auto`。
- 工具定义中不发送 `strict` 参数。
- 请求中会移除 `frequency_penalty` / `presence_penalty` / `thinking_budget`（GLM 不支持这些参数）。
- GLM 仅支持 `max_tokens`：如果只设置了 `max_completion_tokens`（OpenAI 新风格），会自动映射为 `max_tokens`。
- `temperature` 会钳制到 GLM 的取值范围 [0.0, 1.0]（OpenAI 允许到 2.0），`top_p` 钳制到 [0.01, 1.0]；按官方 OpenAI 兼容文档，`temperature = 0` 在 GLM 端点不适用，会自动转换为 `do_sample = false`（确定性输出）。
- `GLMModelProvider` 默认关闭原生结构化输出，因为 GLM 的 `response_format` 仅支持 `json_object`，agent 会自动改走 `generate_response` 工具；如需开启，可在 `ModelCreationContext` 中设置 `nativeStructuredOutput` 选项。

思考相关参数：

- 思考模式（GLM-4.5 及之后的模型）通过额外请求体参数控制，例如 `GenerateOptions.additionalBodyParam("thinking", Map.of("type", "disabled"))`；GLM-4.7 / GLM-5 系列默认开启思考。
- GLM-5.2 支持 `reasoning_effort`（默认 `max`），直接使用 `GenerateOptions.reasoningEffort("max")` 即可透传。
- GLM-5.2 的工具调用流式输出（`tool_stream`）可通过 `GenerateOptions.additionalBodyParam("tool_stream", true)` 开启。

> 旧的 `io.agentscope.extensions.model.openai.formatter.GLMFormatter` 与 `GLMMultiAgentFormatter` 已标记为过时（deprecated），它们现在继承自 `compat.glm` 下的新实现，将在后续版本移除。

## Spring Boot

Spring Boot 应用可以使用 OpenAI starter：

```xml
<dependency>
    <groupId>io.agentscope</groupId>
    <artifactId>agentscope-openai-spring-boot-starter</artifactId>
    <version>${agentscope.version}</version>
</dependency>
```

完整 builder 选项、formatter、credential 和 registry context 细节见 [模型](../../docs/building-blocks/model.md)。
