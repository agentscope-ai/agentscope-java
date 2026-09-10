---
title: OpenAI Official
---

# OpenAI Official 模型

`agentscope-extensions-model-openai-official` 通过官方 OpenAI Java SDK 集成 OpenAI 模型。暂时只支持 Responses API，未来会考虑支持 Chat Completions API。

## 添加依赖

```xml
<dependency>
    <groupId>io.agentscope</groupId>
    <artifactId>agentscope-extensions-model-openai-official</artifactId>
    <version>${agentscope.version}</version>
</dependency>
```

## ModelRegistry

设置 `OPENAI_API_KEY` 后，使用 `openai-official:<model>` 字符串 id：

```java
ReActAgent agent = ReActAgent.builder()
    .name("assistant")
    .model("openai-official:gpt-4o")
    .build();
```

## 显式 Builder

需要自定义 base URL、多 Agent formatter 时使用 Builder：

```java
import io.agentscope.extensions.model.openaiofficial.OpenAIResponsesChatModel;

OpenAIResponsesChatModel model = OpenAIResponsesChatModel.builder()
    .apiKey(System.getenv("OPENAI_API_KEY"))
    .modelName("gpt-4o")
    .stream(true)
    .build();
```

## Spring Boot

本模块暂无专用 Spring Boot starter。

## 推理

通过 `GenerateOptions.reasoningEffort` 控制推理力度（`low` / `medium` / `high` / `minimal`）。要在响应中看到推理内容，需显式开启 `reasoning.summary`：

```java
GenerateOptions options = GenerateOptions.builder()
    .reasoningEffort("high")
    .additionalBodyParam("reasoning.summary", "auto")
    .build();
```

前序轮次的加密推理内容会通过 `Msg.metadata` 自动在多轮对话中回放，无需手动管理。

## 兼容性说明

本模块通过 OpenAI Java SDK 集成，暂时只支持 Responses API，未来会考虑支持 Chat Completions API。以下选项**不支持**，设置非空值时会 fail-fast：`frequencyPenalty`、`presencePenalty`、`topK`、`seed`、`cacheControl`、`thinkingBudget`、`endpointPath`、每请求级 `additionalHeaders` 和 `additionalQueryParams`。

Responses 专有参数通过 `GenerateOptions.additionalBodyParams` 白名单键透传：`reasoning.summary`、`reasoning.context`、`reasoning.mode`、`service_tier`、`prompt_cache_key`、`prompt_cache_options`、`max_tool_calls`、`safety_identifier`。

原生结构化输出默认开启（`supportsNativeStructuredOutput()` 返回 `true`）。SDK 重试已禁用（`maxRetries=0`），重试由 AgentScope 管理。
