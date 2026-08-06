# Kimi Model

`agentscope-extensions-model-openai` provides first-class Kimi (Moonshot AI) support through the OpenAI-compatible model stack. Add the OpenAI model extension module, then use `kimi:<model>` with `ModelRegistry`.

## Add the dependency

```xml
<dependency>
    <groupId>io.agentscope</groupId>
    <artifactId>agentscope-extensions-model-openai</artifactId>
    <version>${agentscope.version}</version>
</dependency>
```

## ModelRegistry

Set `MOONSHOT_API_KEY` or `KIMI_API_KEY`, then use the `kimi:<model>` id:

```java
ReActAgent agent = ReActAgent.builder()
    .name("assistant")
    .model("kimi:kimi-k3") // Resolved internally by ModelRegistry.resolve(modelId)
    .build();
```

The provider defaults to `https://api.moonshot.cn/v1`, strips the `kimi:` prefix before sending the model name, and uses the Kimi formatter from `io.agentscope.extensions.model.openai.compat.kimi`.

## Thinking mode

Pass Kimi thinking options through `GenerateOptions` when resolving the model:

```java
import io.agentscope.core.model.GenerateOptions;
import io.agentscope.core.model.Model;
import io.agentscope.core.model.ModelCreationContext;
import io.agentscope.core.model.ModelRegistry;
import java.util.Map;

Model model = ModelRegistry.resolve(
    "kimi:kimi-k2.6",
    ModelCreationContext.builder()
        .component(
            GenerateOptions.class,
            GenerateOptions.builder()
                .additionalBodyParam("thinking", Map.of("type", "disabled"))
                .maxCompletionTokens(16000)
                .build())
        .build());
```

`kimi-k3` uses the top-level `reasoning_effort` option (`low`, `high`, or `max`). `kimi-k3` and `kimi-k2.7-code` always run with thinking enabled. `kimi-k2.6` and `kimi-k2.5` enable thinking by default, but can disable it with `additionalBodyParam("thinking", Map.of("type", "disabled"))`.

## Compatibility notes

The Kimi formatter adapts OpenAI-style requests to the Kimi chat-completions API. It omits tool schema `strict`, preserves assistant `reasoning_content` in message history, and strips unsupported request fields such as `thinking_budget`.

On `kimi-*` models, sampling parameters such as `temperature`, `top_p`, `n`, `frequency_penalty`, and `presence_penalty` are fixed by the platform and are removed from requests. The `moonshot-v1` series keeps those parameters. Kimi documents `max_completion_tokens`, so `max_tokens` is mapped to `max_completion_tokens` when `max_completion_tokens` is not already set.

`reasoning_effort` is kept only for `kimi-k3`. For K2.x thinking controls, pass the `thinking` body parameter through `GenerateOptions.additionalBodyParam`.

`tool_choice=auto` and `tool_choice=none` are supported broadly. `tool_choice=required` is degraded to `auto` on K2.x models. Forcing a specific function is incompatible with thinking enabled, so it is degraded to `auto` on `kimi-k3`, on `kimi-k2.7-code`, and on `kimi-k2.6` / `kimi-k2.5` unless `thinking.type` is explicitly set to `disabled`.

Structured output uses the normal AgentScope fallback behavior by default.

For compatible or self-hosted endpoints, pass `baseUrl`, `endpointPath`, generation options, or formatter overrides through `ModelCreationContext`.
