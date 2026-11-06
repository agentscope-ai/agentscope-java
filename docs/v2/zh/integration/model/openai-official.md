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

## 文档输入

通过用户消息中的 `DocumentBlock` 发送文档。默认 formatter 和
`ResponsesMultiAgentFormatter` 都会将文档保留为原生 `input_file` 内容，并保持
文档与文本、图片的排列顺序。文件格式和模型支持范围遵循
[OpenAI 文件输入指南](https://developers.openai.com/api/docs/guides/file-inputs)，
本适配器不将文档限制为 PDF。

```java
import io.agentscope.core.message.DocumentBlock;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.message.URLSource;
import io.agentscope.core.message.UserMessage;
import java.util.List;

DocumentBlock document = DocumentBlock.builder()
    .source(new URLSource("https://example.com/report.pdf"))
    .build();

model.stream(List.of(UserMessage.builder()
        .content(TextBlock.builder().text("总结这份报告。").build(), document)
        .build()), null, null)
    .doOnNext(System.out::println)
    .blockLast();
```

`content(...)` 会替换消息内容，请在一次调用中传入所有内容块。

HTTP(S) 来源会直接作为 `file_url` 发送给 OpenAI，AgentScope 不下载远程文件。
本地路径和 `file://` URI 会自动读取并编码为 `file_data`，读取时沿用现有的单文件
50 MB 限制，且只接受普通文件。请只使用可信的本地路径：适配器会读取传入的路径，
不会将其限制在某个目录内。

通过 `model.stream(...)` 调用时，请求映射会在订阅时在线程池中执行。每次重新订阅
都会重新读取本地文档；提供方内部重试会复用已经准备好的请求。直接调用
`ResponsesMultiAgentFormatter.formatHistory(...)` 时，映射会在调用线程上同步执行。
对于跨请求复用的文档，可以先上传文件，再使用 `FileIdSource`，避免重复读取和编码。

请求准备阶段遵循配置的执行超时，且不会重试。提供方调用仍遵循原有的超时和重试
配置。准备阶段与提供方调用分别计时，不共享一个覆盖整个请求的截止时间。

使用 `Base64Source` 传入已经编码的文件时，提供原始 Base64 字符串和 MIME 类型。
本地文档的 MIME 类型优先使用 `DocumentBlock.mimeType`，其次使用
`URLSource.mimeType`，最后根据扩展名推断 PDF、Word、电子表格、演示文稿和文本等
常见文档格式。无法推断本地文件的 MIME 类型时，映射会报错，提示显式填写 MIME 类型。

```java
import io.agentscope.core.message.Base64Source;
import io.agentscope.core.message.FileIdSource;
import java.nio.file.Path;

DocumentBlock local = DocumentBlock.builder()
    .source(new URLSource(Path.of("report.pdf").toUri().toString()))
    .build();

DocumentBlock inline = DocumentBlock.builder()
    .source(new Base64Source("text/csv", "bmFtZSx2YWx1ZQphLDEK"))
    .name("report.csv")
    .build();

DocumentBlock uploaded = DocumentBlock.builder()
    .source(new FileIdSource("openai", "file-123"))
    .build();
```

内联文件优先使用 `DocumentBlock.name` 作为文件名。未填写时，本地文件保留原文件名；
Base64 来源会根据 MIME 类型生成默认名称，例如 `document.pdf`、`document.txt`、
`document.docx`、`document.xlsx`。如果没有对应的默认名称，需要显式填写 `name`。
这些默认名称不限制提供了文件名时可发送的文件格式。

文件 ID 的 provider 接受 `openai` 和 `openai-official`，并映射为 `file_id`。
文件需要先由调用方单独上传，再引用返回的 ID。provider 不匹配、URL 协议不支持或
本地文件读取失败时，会明确报错，不会丢弃文档。文档映射失败会终止整个请求，
多 agent 历史映射也遵循这一行为。本功能仅处理用户消息中的文档输入，工具结果中的
文档、文件上传和响应端文件输出不在本次范围内。

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

Hosted `tool_search` 通过 `OpenAIServerTool` 配置。可为单个本地函数工具标注
`@Tool(deferLoading = true)`，延迟加载其参数 schema。未标注的工具保持立即可用；
配置了 `deferLoading` 但未启用 hosted 搜索声明时会快速失败。

使用 client-executed 搜索时，在 agent 上注册 `ToolSearchMiddleware`。
Middleware 调用配置的 `ToolSearchStrategy`；模型适配层声明原生客户端搜索，将
`tool_search_call` 交给现有权限和执行链处理，并通过 `tool_search_output` 重放命中的
工具定义。流式与非流式响应使用相同的调用表示。同一请求不能同时声明 hosted 和
client-executed 搜索。
配置与普通函数调用回退方式见[按需搜索工具](/v2/zh/docs/building-blocks/tool#按需搜索工具)。

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
