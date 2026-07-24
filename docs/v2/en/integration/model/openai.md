# OpenAI Model

`agentscope-extensions-model-openai` integrates OpenAI Chat Completions-style models. It is also the module to use for OpenAI-compatible endpoints such as DeepSeek, GLM, Kimi, and similar services when their wire format follows the OpenAI API.

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

## Kimi (Moonshot AI)

Kimi has a dedicated adapter in the `io.agentscope.extensions.model.openai.compat.kimi` package. It reuses `OpenAIChatModel` under the hood but is preconfigured for the Kimi open platform endpoint (`https://api.moonshot.cn/v1`).

Set `MOONSHOT_API_KEY` (or `KIMI_API_KEY`), then use the `kimi:<model>` id:

```java
ReActAgent agent = ReActAgent.builder()
    .name("assistant")
    .model("kimi:kimi-k3") // Resolved by KimiModelProvider through ModelRegistry
    .build();
```

Or build the model explicitly with a Kimi formatter:

```java
import io.agentscope.extensions.model.openai.OpenAIChatModel;
import io.agentscope.extensions.model.openai.compat.kimi.KimiFormatter;

OpenAIChatModel model = OpenAIChatModel.builder()
    .apiKey(System.getenv("MOONSHOT_API_KEY"))
    .baseUrl("https://api.moonshot.cn/v1")
    .modelName("kimi-k3")
    .formatter(new KimiFormatter()) // or KimiMultiAgentFormatter for multi-agent prompts
    .stream(true)
    .build();
```

The Kimi formatters adapt requests to the latest official API:

- On `kimi-*` models (kimi-k3, kimi-k2.7-code, kimi-k2.6, etc.), `temperature` / `top_p` / `frequency_penalty` / `presence_penalty` are fixed by the platform and the API rejects other values, so these parameters are stripped from requests; the `moonshot-v1` series still accepts them and they are passed through.
- `reasoning_effort` (`low` / `high` / `max`, default `max`) is only supported by `kimi-k3` and is stripped on other models; use the `thinking` body parameter on K2.x models instead.
- `thinking_budget` is not a Kimi parameter and is always stripped.
- Kimi only supports `max_tokens`: when only `max_completion_tokens` (the newer OpenAI style) is set, it is mapped to `max_tokens`. Note that reasoning tokens (`reasoning_content`) count towards `max_tokens`; the official guide recommends `max_tokens >= 16000` for thinking models.
- `tool_choice`: `auto` / `none` are supported by all models; `required` is only supported by `kimi-k3` and is degraded to `auto` on the K2.x series; forcing a specific function is incompatible with thinking enabled (HTTP 400), so it is degraded to `auto` on always-thinking models (`kimi-k3`, `kimi-k2.7-code`) and passed through otherwise (disable thinking via the `thinking` parameter first on `kimi-k2.6`).
- The `strict` parameter in tool definitions is not sent (not documented by Kimi).
- `reasoning_content` on assistant history messages is passed back as-is, satisfying Preserved Thinking on `kimi-k3` / `kimi-k2.7-code` and `thinking.keep = "all"` on `kimi-k2.6`.
- `KimiModelProvider` disables native structured output by default because the Kimi `response_format` only supports `json_object` (JSON Mode); agents fall back to the `generate_response` tool. It also disables structured output alongside tools by default, because Kimi prioritises `response_format` over tool invocations. Both can be re-enabled with the `nativeStructuredOutput` / `nativeStructuredOutputWithTools` options in `ModelCreationContext`.

Thinking-related parameters:

- `kimi-k2.6` / `kimi-k2.5` control thinking through the `thinking` body parameter, e.g. `GenerateOptions.additionalBodyParam("thinking", Map.of("type", "disabled"))`; `kimi-k2.6` also supports `Map.of("type", "enabled", "keep", "all")` to enable Preserved Thinking.
- `kimi-k2.7-code` always thinks and Preserved Thinking cannot be disabled; do not pass a `thinking` parameter.
- `kimi-k3` always thinks and uses the top-level `reasoning_effort` to configure thinking effort; use `GenerateOptions.reasoningEffort("high")`, which is passed through directly.

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
