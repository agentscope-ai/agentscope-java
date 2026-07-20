# OpenAI 模型

`agentscope-extensions-model-openai` 接入 OpenAI Chat Completions 风格的模型。OpenAI 兼容端点也使用这个适配模块，例如 DeepSeek、GLM、Kimi 等遵循 OpenAI API 载荷格式的服务。

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

## Kimi（月之暗面 / Moonshot AI）

Kimi 在 `io.agentscope.extensions.model.openai.compat.kimi` 包中提供了专用适配。它底层复用 `OpenAIChatModel`，但默认配置为 Kimi 开放平台端点（`https://api.moonshot.cn/v1`）。

设置 `MOONSHOT_API_KEY`（或 `KIMI_API_KEY`）后，使用 `kimi:<model>` 字符串 id：

```java
ReActAgent agent = ReActAgent.builder()
    .name("assistant")
    .model("kimi:kimi-k3") // 底层由 KimiModelProvider 通过 ModelRegistry 解析
    .build();
```

也可以显式构建并指定 Kimi formatter：

```java
import io.agentscope.extensions.model.openai.OpenAIChatModel;
import io.agentscope.extensions.model.openai.compat.kimi.KimiFormatter;

OpenAIChatModel model = OpenAIChatModel.builder()
    .apiKey(System.getenv("MOONSHOT_API_KEY"))
    .baseUrl("https://api.moonshot.cn/v1")
    .modelName("kimi-k3")
    .formatter(new KimiFormatter()) // 多智能体提示词场景使用 KimiMultiAgentFormatter
    .stream(true)
    .build();
```

Kimi formatter 按最新官方 API 对请求做了以下适配：

- `kimi-*` 系列模型（kimi-k3、kimi-k2.7-code、kimi-k2.6 等）的 `temperature` / `top_p` / `frequency_penalty` / `presence_penalty` 由平台固定、传入其他值会报错，因此这些参数会从请求中移除；`moonshot-v1` 系列仍可修改，会正常透传。
- `reasoning_effort`（`low` / `high` / `max`，默认 `max`）仅 `kimi-k3` 支持，其他模型上会移除；K2.x 模型请改用 `thinking` 请求体参数。
- `thinking_budget` 不是 Kimi 参数，始终移除。
- Kimi 仅支持 `max_tokens`：如果只设置了 `max_completion_tokens`（OpenAI 新风格），会自动映射为 `max_tokens`。注意思考内容（`reasoning_content`）也计入 `max_tokens`，官方建议思考模型设置 `max_tokens >= 16000`。
- `tool_choice`：`auto` / `none` 全系支持；`required` 仅 `kimi-k3` 支持，K2.x 系列会降级为 `auto`；指定具体函数与思考开启不兼容（HTTP 400），在始终思考的模型（`kimi-k3`、`kimi-k2.7-code`）上会降级为 `auto`，其余模型正常透传（`kimi-k2.6` 上使用时请先通过 `thinking` 参数关闭思考）。
- 工具定义中不发送 `strict` 参数（Kimi 文档未支持）。
- assistant 历史消息中的 `reasoning_content` 会原样回传，满足 `kimi-k3` / `kimi-k2.7-code` 的保留式思考（Preserved Thinking）以及 `kimi-k2.6` 的 `thinking.keep = "all"` 要求。
- `KimiModelProvider` 默认关闭原生结构化输出，因为 Kimi 的 `response_format` 仅支持 `json_object`（JSON Mode），agent 会自动改走 `generate_response` 工具；同时默认关闭"结构化输出与工具共存"（Kimi 会优先遵循 `response_format` 而跳过工具调用）。两者都可以在 `ModelCreationContext` 中通过 `nativeStructuredOutput` / `nativeStructuredOutputWithTools` 选项显式开启。

思考相关参数：

- `kimi-k2.6` / `kimi-k2.5` 通过 `thinking` 请求体参数控制思考，例如 `GenerateOptions.additionalBodyParam("thinking", Map.of("type", "disabled"))`；`kimi-k2.6` 还支持 `Map.of("type", "enabled", "keep", "all")` 开启保留式思考。
- `kimi-k2.7-code` 始终开启思考且保留式思考不可关闭，无需（也不应）传入 `thinking` 参数。
- `kimi-k3` 始终思考，使用顶层 `reasoning_effort` 配置思考力度，直接用 `GenerateOptions.reasoningEffort("high")` 即可透传。

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
