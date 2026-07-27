# GLM Model

`agentscope-extensions-model-openai` provides first-class GLM (Zhipu AI / Z.AI) support through the OpenAI-compatible model stack. Add the OpenAI model extension module, then use `glm:<model>` with `ModelRegistry`.

## Add the dependency

```xml
<dependency>
    <groupId>io.agentscope</groupId>
    <artifactId>agentscope-extensions-model-openai</artifactId>
    <version>${agentscope.version}</version>
</dependency>
```

## ModelRegistry

Set `ZAI_API_KEY`, `GLM_API_KEY`, or `ZHIPUAI_API_KEY`, then use the `glm:<model>` id:

```java
ReActAgent agent = ReActAgent.builder()
    .name("assistant")
    .model("glm:glm-5.2") // Resolved internally by ModelRegistry.resolve(modelId)
    .build();
```

The provider defaults to `https://open.bigmodel.cn/api/paas/v4`, strips the `glm:` prefix before sending the model name, and uses the GLM formatter from `io.agentscope.extensions.model.openai.compat.glm`.

## Thinking mode

Pass GLM thinking options through `GenerateOptions` when resolving the model:

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

GLM-4.7 and GLM-5 series enable thinking by default. GLM-5.2 also supports `reasoning_effort`, and streaming tool-call arguments can be enabled with `additionalBodyParam("tool_stream", true)`.

## Compatibility notes

The GLM formatter adapts OpenAI-style requests to the GLM chat-completions API. It ensures at least one user message exists, removes unsupported message `name` fields, omits tool schema `strict`, and strips unsupported request fields such as `frequency_penalty`, `presence_penalty`, `thinking_budget`, and `stream_options`.

GLM only supports `max_tokens`, so `max_completion_tokens` is mapped to `max_tokens` when `max_tokens` is not already set. `temperature` and `top_p` are clamped to the GLM-supported ranges.

GLM only accepts `tool_choice=auto`. Forced choices are degraded to `auto`; `ToolChoice.None` removes tools from the request to preserve the no-tool-call contract.

Structured output uses the normal AgentScope fallback behavior by default because GLM `response_format` only supports `json_object`. Only enable native structured output when you have confirmed the target endpoint supports the behavior you need.

For compatible or self-hosted endpoints, pass `baseUrl`, `endpointPath`, generation options, or formatter overrides through `ModelCreationContext`.
