---
title: OpenAI Official
zh_link: /v2/zh/integration/model/openai-official
---

# OpenAI Official Model

`agentscope-extensions-model-openai-official` integrates OpenAI models through the official OpenAI Java SDK. It currently supports only the Responses API; support for the Chat Completions API may be added in the future.

## Add the dependency

```xml
<dependency>
    <groupId>io.agentscope</groupId>
    <artifactId>agentscope-extensions-model-openai-official</artifactId>
    <version>${agentscope.version}</version>
</dependency>
```

## ModelRegistry

Set `OPENAI_API_KEY`, then use the `openai-official:<model>` id:

```java
ReActAgent agent = ReActAgent.builder()
    .name("assistant")
    .model("openai-official:gpt-4o")
    .build();
```

## Explicit builder

Use the builder when you need a custom base URL, or a multi-agent formatter:

```java
import io.agentscope.extensions.model.openaiofficial.OpenAIResponsesChatModel;

OpenAIResponsesChatModel model = OpenAIResponsesChatModel.builder()
    .apiKey(System.getenv("OPENAI_API_KEY"))
    .modelName("gpt-4o")
    .stream(true)
    .build();
```

## Server-side built-in tools

OpenAI Official Documents: https://developers.openai.com/api/docs/guides/tools#available-tools

Enable provider-executed built-in tools for the Responses API with `serverTools`:

```java
import com.openai.models.responses.Tool;
import com.openai.models.responses.WebSearchTool;
import io.agentscope.extensions.model.openaiofficial.tool.OpenAIServerTool;

OpenAIResponsesChatModel model = OpenAIResponsesChatModel.builder()
    .apiKey(System.getenv("OPENAI_API_KEY"))
    .modelName("gpt-5")
    .serverTools(List.of(OpenAIServerTool.of(
        Tool.ofWebSearch(WebSearchTool.builder()
            .type(WebSearchTool.Type.WEB_SEARCH)
            .build()))))
    .build();
```

Supported tool types are `web_search`, `code_interpreter`, `image_generation`, and `tool_search`.

Built-in tool calls and results are preserved as server-tool-marked `ToolUseBlock` / `ToolResultBlock` blocks, and the raw Responses item is stored in metadata for replay in later turns.

`tool_search` supports only "Hosted" mode; "Client-executed" mode is not supported yet. Mark individual local function tools with `@Tool(deferLoading = true)` to defer their parameter schemas until the model loads them through hosted tool search. Unmarked tools stay eagerly available, and requesting `deferLoading` without a `tool_search` server tool fails fast.

Message annotations, including `container_file_citation`, are preserved in the `openai.response.citations` response metadata. Code Interpreter image outputs are represented as `DataBlock` blocks with `URLSource`, and the container ID is retained in the tool result metadata.

`image_generation` results are returned as a `DataBlock` with a `Base64Source`. If `partial_images` is enabled, each `response.image_generation_call.partial_image` event is emitted as a `RUNNING` server-tool result containing a preview `DataBlock`, and the terminal response replaces it with the final image.

## Spring Boot

Spring Boot applications can use the OpenAI Official starter:

```xml
<dependency>
    <groupId>io.agentscope</groupId>
    <artifactId>agentscope-openai-official-spring-boot-starter</artifactId>
    <version>${agentscope.version}</version>
</dependency>
```

```yaml
agentscope:
  model:
    provider: openai-official
  openai-official:
    api-key: ${OPENAI_API_KEY}
    model-name: gpt-4o
```

## Reasoning

Control reasoning effort via `GenerateOptions.reasoningEffort` (`low` / `medium` / `high` / `minimal`). To make reasoning visible in the response, explicitly enable `reasoning.summary`:

```java
GenerateOptions options = GenerateOptions.builder()
    .reasoningEffort("high")
    .additionalBodyParam("reasoning.summary", "auto")
    .build();
```

Encrypted reasoning content from previous turns is automatically replayed in multi-turn conversations via `Msg.metadata`. No manual management is needed.

## Compatibility notes

This module integrates via the OpenAI Java SDK and currently supports only the Responses API; Chat Completions API support may be added in the future. The following options are **not supported** and will fail-fast when set: `frequencyPenalty`, `presencePenalty`, `topK`, `seed`, `cacheControl`, `thinkingBudget`, `endpointPath`, per-request `additionalHeaders`, and per-request `additionalQueryParams`.

Responses-specific parameters are available through `GenerateOptions.additionalBodyParams` with a whitelist: `reasoning.summary`, `reasoning.context`, `reasoning.mode`, `service_tier`, `prompt_cache_key`, `prompt_cache_options`, `max_tool_calls`, `safety_identifier`, `store`, and `previous_response_id`.

`store` controls server-side response storage and defaults to `false`. To use OpenAI's server-side conversation chaining, store the first response, read its ID from response metadata (`openai.response.id`), and pass that ID as `previous_response_id` in a later request. When `previous_response_id` is used, send only the new turn's input rather than repeating the full conversation history.

Native structured output is always enabled (`supportsNativeStructuredOutput()` returns `true`). The SDK retry is disabled (`maxRetries=0`); retry is managed by AgentScope.
