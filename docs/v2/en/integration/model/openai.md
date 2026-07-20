# OpenAI Model

`agentscope-extensions-model-openai` integrates OpenAI Chat Completions-style models. It is also the module to use for OpenAI-compatible endpoints such as DeepSeek, GLM, and similar services when their wire format follows the OpenAI API.

## Add the dependency

```xml
<dependency>
    <groupId>io.agentscope</groupId>
    <artifactId>agentscope-extensions-model-openai</artifactId>
    <version>${agentscope.version}</version>
</dependency>
```

## ModelRegistry

Set `OPENAI_API_KEY`, then use the `openai:<model>` id:

```java
ReActAgent agent = ReActAgent.builder()
    .name("assistant")
    .model("openai:gpt-4.1-mini") // Resolved internally by ModelRegistry.resolve(modelId)
    .build();
```

## Explicit builder

Use the builder when you need a custom endpoint, formatter, transport, or generation options:

```java
import io.agentscope.extensions.model.openai.OpenAIChatModel;

OpenAIChatModel model = OpenAIChatModel.builder()
    .apiKey(System.getenv("OPENAI_API_KEY"))
    .modelName("gpt-4.1-mini")
    .stream(true)
    .build();
```

For compatible endpoints, set `baseUrl(...)` and the model name expected by that service.

## GLM (Zhipu AI)

GLM has a dedicated adapter in the `io.agentscope.extensions.model.openai.compat.glm` package. It reuses `OpenAIChatModel` under the hood but is preconfigured for the Zhipu open platform endpoint (`https://open.bigmodel.cn/api/paas/v4`).

Set `GLM_API_KEY` (or `ZHIPUAI_API_KEY`), then use the `glm:<model>` id:

```java
ReActAgent agent = ReActAgent.builder()
    .name("assistant")
    .model("glm:glm-5.2") // Resolved by GLMModelProvider through ModelRegistry
    .build();
```

Or build the model explicitly with a GLM formatter:

```java
import io.agentscope.extensions.model.openai.OpenAIChatModel;
import io.agentscope.extensions.model.openai.compat.glm.GLMFormatter;

OpenAIChatModel model = OpenAIChatModel.builder()
    .apiKey(System.getenv("GLM_API_KEY"))
    .baseUrl("https://open.bigmodel.cn/api/paas/v4")
    .modelName("glm-5.2")
    .formatter(new GLMFormatter()) // or GLMMultiAgentFormatter for multi-agent prompts
    .stream(true)
    .build();
```

The GLM formatters adapt requests to the latest Zhipu API:

- Ensure at least one user message exists (the API returns error 1214 otherwise).
- `tool_choice` only supports `auto`; other values are degraded to `auto`.
- The `strict` parameter in tool definitions is not sent.
- `frequency_penalty` / `presence_penalty` / `thinking_budget` are stripped from requests (not supported by GLM).
- GLM only supports `max_tokens`: when only `max_completion_tokens` (the newer OpenAI style) is set, it is mapped to `max_tokens`.
- `temperature` is clamped to the GLM range [0.0, 1.0] (OpenAI allows up to 2.0) and `top_p` to [0.01, 1.0]; per the official OpenAI compatibility guide, `temperature = 0` is not applicable on the GLM endpoint and is translated to `do_sample = false` (deterministic decoding).
- `GLMModelProvider` disables native structured output by default because GLM `response_format` only supports `json_object`; agents fall back to the `generate_response` tool. Re-enable it with the `nativeStructuredOutput` option in `ModelCreationContext` if needed.

Thinking-related parameters:

- Thinking mode (GLM-4.5 and later) is controlled through an additional body parameter, e.g. `GenerateOptions.additionalBodyParam("thinking", Map.of("type", "disabled"))`; GLM-4.7 / GLM-5 series enable thinking by default.
- GLM-5.2 supports `reasoning_effort` (default `max`); use `GenerateOptions.reasoningEffort("max")`, which is passed through directly.
- GLM-5.2 streaming tool-call arguments (`tool_stream`) can be enabled via `GenerateOptions.additionalBodyParam("tool_stream", true)`.

> The old `io.agentscope.extensions.model.openai.formatter.GLMFormatter` and `GLMMultiAgentFormatter` classes are deprecated; they now extend the `compat.glm` implementations and will be removed in a future release.

## Spring Boot

Spring Boot applications can use the OpenAI starter:

```xml
<dependency>
    <groupId>io.agentscope</groupId>
    <artifactId>agentscope-openai-spring-boot-starter</artifactId>
    <version>${agentscope.version}</version>
</dependency>
```

Full builder options, formatters, credentials, and registry context details are covered in [Model](../../docs/building-blocks/model.md).
