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

## Document input

Send documents in user messages with `DocumentBlock`. Both the default formatter
and `ResponsesMultiAgentFormatter` preserve documents as native `input_file`
content parts, including their position among text and images. File formats and
model support follow the [OpenAI file inputs guide](https://developers.openai.com/api/docs/guides/file-inputs);
this adapter does not restrict documents to PDF.

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
        .content(TextBlock.builder().text("Summarize this report.").build(), document)
        .build()), null, null)
    .doOnNext(System.out::println)
    .blockLast();
```

`content(...)` replaces the message content; pass all blocks in one call.

HTTP(S) sources are passed to OpenAI as `file_url` without downloading them in
AgentScope. Local paths and `file://` URIs are read and encoded as `file_data`;
local reads require regular files and use the existing 50 MB per-file limit.
Only use trusted local paths: the adapter reads the supplied path without
restricting it to a directory.

When using `model.stream(...)`, request mapping runs on a worker thread at
subscription time. Each new subscription rereads local documents; provider
retries reuse the prepared request. Direct calls to
`ResponsesMultiAgentFormatter.formatHistory(...)` run synchronously on the caller's
thread. For documents reused across requests, a pre-uploaded `FileIdSource` avoids
repeated local reads and encoding.

Request preparation uses the configured execution timeout and is not retried.
Provider calls retain their existing timeout and retry configuration. Preparation
and provider calls have separate timeout budgets, not a shared overall deadline.

For inline data, use `Base64Source` with raw Base64 bytes and the MIME type. For
local documents, `DocumentBlock.mimeType` takes priority over `URLSource.mimeType`
and extension-based inference for common document formats, including PDF, Word,
spreadsheets, presentations, and text files. If the local MIME type cannot be
inferred, mapping fails with an error asking for an explicit MIME type.

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

Inline filenames use `DocumentBlock.name` when supplied. Local files otherwise
keep their original filename; Base64 sources use a MIME-based default such as
`document.pdf`, `document.txt`, `document.docx`, or `document.xlsx`. If no default
is known for a MIME type, provide `name` explicitly. These defaults do not limit
which formats can be sent with an explicit filename.

File IDs accept provider `openai` or `openai-official` and are sent as `file_id`.
Upload files separately before referencing their IDs. Invalid providers,
unsupported URL schemes, and local file read failures raise an error rather than
dropping the document. A document mapping failure aborts the entire request,
including multi-agent history mapping. This support covers user-message input;
document tool results, file uploads, and response-side file output are outside its
scope.

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

Hosted `tool_search` is configured through `OpenAIServerTool`. Mark individual local
function tools with `@Tool(deferLoading = true)` to defer their parameter schemas.
Unmarked tools stay eagerly available, and requesting `deferLoading` without a
hosted search declaration fails fast.

For client-executed search, register `ToolSearchMiddleware` on the agent instead.
The middleware uses your `ToolSearchStrategy`; the model adapter declares native
client search, dispatches `tool_search_call` locally through the normal permission
and execution path, and replays `tool_search_output` with the discovered schemas.
Streaming and non-streaming responses use the same call representation. Hosted
and client-executed search declarations cannot be combined in one request.
See [on-demand tool search](/v2/en/docs/building-blocks/tool#on-demand-tool-search)
for configuration and the ordinary-function fallback.

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
