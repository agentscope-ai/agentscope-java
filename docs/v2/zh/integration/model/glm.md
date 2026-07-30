# GLM 模型

`agentscope-extensions-model-openai` 通过 OpenAI 兼容模型栈提供 GLM（智谱 / Z.AI）的一等支持。引入 OpenAI 模型扩展模块后，可以通过 `ModelRegistry` 使用 `glm:<model>`。

## 添加依赖

```xml
<dependency>
    <groupId>io.agentscope</groupId>
    <artifactId>agentscope-extensions-model-openai</artifactId>
    <version>${agentscope.version}</version>
</dependency>
```

## ModelRegistry

设置 `ZAI_API_KEY`、`GLM_API_KEY` 或 `ZHIPUAI_API_KEY` 后，使用 `glm:<model>` 字符串 id：

```java
ReActAgent agent = ReActAgent.builder()
    .name("assistant")
    .model("glm:glm-5.2") // 底层由 ModelRegistry.resolve(modelId) 解析
    .build();
```

Provider 默认使用 `https://open.bigmodel.cn/api/paas/v4` ，发送请求前会去掉 `glm:` 前缀，并使用 `io.agentscope.extensions.model.openai.compat.glm` 下的 GLM formatter。

## 思考模式

通过 `ModelCreationContext` 解析模型时，可以用 `GenerateOptions` 传入 GLM 思考相关参数：

```java
import io.agentscope.core.model.GenerateOptions;
import io.agentscope.core.model.Model;
import io.agentscope.core.model.ModelCreationContext;
import io.agentscope.core.model.ModelRegistry;
import java.util.Map;

Model model = ModelRegistry.resolve(
    "glm:glm-5.2",
    ModelCreationContext.builder()
        .component(
            GenerateOptions.class,
            GenerateOptions.builder()
                .additionalBodyParam("thinking", Map.of("type", "disabled"))
                .reasoningEffort("max")
                .build())
        .build());
```

GLM-4.7 和 GLM-5 系列默认开启思考。GLM-5.2 还支持 `reasoning_effort`；如果需要流式工具调用参数，可以通过 `additionalBodyParam("tool_stream", true)` 开启。

## 兼容性说明

GLM formatter 会把 OpenAI 风格请求适配为 GLM chat-completions API 支持的格式。它会确保至少存在一条 user 消息，移除 GLM 不支持的消息 `name` 字段，省略工具 schema 的 `strict` 字段，并移除 `frequency_penalty`、`presence_penalty`、`thinking_budget`、`stream_options` 等不支持的请求字段。

GLM 只支持 `max_tokens`，因此当只设置了 `max_completion_tokens` 且没有设置 `max_tokens` 时，会自动映射到 `max_tokens`。`temperature` 和 `top_p` 会被钳制到 GLM 支持的取值范围。

GLM 只接受 `tool_choice=auto`。强制工具选择会降级为 `auto`；`ToolChoice.None` 会从请求中移除 tools，以保留“不调用工具”的语义。

结构化输出默认使用 AgentScope 的 fallback 行为，因为 GLM 的 `response_format` 仅支持 `json_object`。只有在你明确确认目标端点支持所需行为时，才需要手动开启 native structured output。

使用兼容端点或自托管端点时，可以通过 `ModelCreationContext` 传入 `baseUrl`、`endpointPath`、生成参数或 formatter 覆盖。
