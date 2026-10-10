---
title: OpenAI Official
en_link: /v2/en/integration/model/openai-official
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

## 服务端内置工具

OpenAI 官方文档: https://developers.openai.com/api/docs/guides/tools#available-tools

Responses API 的 provider-executed built-in tools 可通过 `serverTools` 启用：

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

支持 `web_search`、`code_interpreter`、`image_generation` 和 `tool_search`。

模型返回的内置工具调用和结果会保留为 server-tool 标记的 `ToolUseBlock` / `ToolResultBlock`，并将原始 Responses item 存至 metadata 在后续轮次回放。

`tool_search` 仅支持 "Hosted" 模式，暂不支持 "Client-executed" 模式。 可为单个本地函数工具标注 `@Tool(deferLoading = true)`，其参数 schema 会延迟到模型通过 hosted tool search 加载时才进入上下文。未标注的工具保持立即可用；配置了 `deferLoading` 但未启用 `tool_search` server tool 时会快速失败。

消息 annotations（包括 `container_file_citation`）会保留在 `openai.response.citations` 响应 metadata 中。Code Interpreter 的图片输出会表示为带 `URLSource` 的 `DataBlock`，容器 ID 会保留在工具结果 metadata 中。

`image_generation` 的结果会映射为带 `Base64Source` 的 `DataBlock`。启用 `partial_images` 后，每个 `response.image_generation_call.partial_image` 事件会以 `RUNNING` 状态的 server-tool result 输出预览 `DataBlock`，terminal response 会用最终图片替换它。

## Spring Boot

Spring Boot 应用可以使用 OpenAI Official starter：

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

Responses 专有参数通过 `GenerateOptions.additionalBodyParams` 白名单键透传：`reasoning.summary`、`reasoning.context`、`reasoning.mode`、`service_tier`、`prompt_cache_key`、`prompt_cache_options`、`max_tool_calls`、`safety_identifier`、`store`、`previous_response_id`。

`store` 控制服务端响应存储，默认为 `false`。如需使用 OpenAI 服务端会话续链，先存储第一轮响应，从响应 metadata 的 `openai.response.id` 读取 ID，并在后续请求中传入 `previous_response_id`。使用 `previous_response_id` 时应只发送本轮新增 input，不要重复完整会话历史。

原生结构化输出默认开启（`supportsNativeStructuredOutput()` 返回 `true`）。SDK 重试已禁用（`maxRetries=0`），重试由 AgentScope 管理。
