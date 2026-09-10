---
title: OpenAI Official
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

## Spring Boot

There is no dedicated Spring Boot starter for this module yet.

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

Responses-specific parameters are available through `GenerateOptions.additionalBodyParams` with a whitelist: `reasoning.summary`, `reasoning.context`, `reasoning.mode`, `service_tier`, `prompt_cache_key`, `prompt_cache_options`, `max_tool_calls`, `safety_identifier`.

Native structured output is always enabled (`supportsNativeStructuredOutput()` returns `true`). The SDK retry is disabled (`maxRetries=0`); retry is managed by AgentScope.
