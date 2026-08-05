# MiniMax Model

`agentscope-extensions-model-openai` provides first-class MiniMax support through the OpenAI-compatible model stack. Add the OpenAI model extension module, then use `minimax:<model>` with `ModelRegistry`.

## Add the dependency

```xml
<dependency>
    <groupId>io.agentscope</groupId>
    <artifactId>agentscope-extensions-model-openai</artifactId>
    <version>${agentscope.version}</version>
</dependency>
```

## ModelRegistry

Set `MINIMAX_API_KEY`, then use the `minimax:<model>` id:

```java
ReActAgent agent = ReActAgent.builder()
    .name("assistant")
    .model("minimax:MiniMax-M3") // Resolved internally by ModelRegistry.resolve(modelId)
    .build();
```

The provider base URL defaults to `https://api.minimaxi.com/v1`. `OpenAIClient` appends the default chat completions endpoint, so the final request URL is `https://api.minimaxi.com/v1/chat/completions`, matching the MiniMax OpenAI-compatible API. The provider strips the `minimax:` prefix before sending the model name and uses the MiniMax formatter from `io.agentscope.extensions.model.openai.compat.minimax`.

## Thinking mode

Pass MiniMax thinking options through `ModelCreationContext` when resolving the model:

```java
import io.agentscope.core.model.Model;
import io.agentscope.core.model.ModelCreationContext;
import io.agentscope.core.model.ModelRegistry;

Model model = ModelRegistry.resolve(
    "minimax:MiniMax-M3",
    ModelCreationContext.builder()
        .enableThinking(false)
        .build());
```

`enableThinking(false)` sends `thinking: {"type": "disabled"}` and `enableThinking(true)` sends `thinking: {"type": "adaptive"}`. MiniMax-M3 uses adaptive thinking by default when `thinking` is omitted; M2.x models keep thinking enabled even when disabled is requested. The formatter enables `reasoning_split` by default so MiniMax thinking content can be parsed as `ThinkingBlock`.

## Compatibility notes

The MiniMax formatter adapts OpenAI-style requests to the MiniMax OpenAI-compatible Chat Completions API. It maps `max_tokens` to `max_completion_tokens`, because MiniMax marks `max_tokens` as deprecated.

MiniMax tool definitions support function tools, but the official schema does not include the tool schema `strict` field, so the default formatter omits `strict` even when a tool is registered with strict schema validation. MiniMax also does not document `tool_choice`, so explicit tool-choice settings are removed from MiniMax requests.

The formatter removes unsupported OpenAI-only request fields such as `reasoning_effort`, `frequency_penalty`, `presence_penalty`, `thinking_budget`, `parallel_tool_calls`, `response_format`, and `seed`. Structured output uses the normal AgentScope fallback behavior by default because MiniMax does not document OpenAI `response_format` support for schema-constrained output.

For compatible or self-hosted endpoints, pass `baseUrl`, `endpointPath`, generation options, or formatter overrides through `ModelCreationContext`.
