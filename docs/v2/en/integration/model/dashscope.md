---
title: DashScope
zh_link: /v2/zh/integration/model/dashscope
---

`agentscope-extensions-model-dashscope` integrates Alibaba Cloud DashScope Qwen models, including multimodal and reasoning-capable Qwen models.

## Add the dependency

```xml
<dependency>
    <groupId>io.agentscope</groupId>
    <artifactId>agentscope-extensions-model-dashscope</artifactId>
    <version>${agentscope.version}</version>
</dependency>
```

## ModelRegistry

Set `DASHSCOPE_API_KEY`, then use either `dashscope:<model>` or the Qwen short form:

```java
ReActAgent agent = ReActAgent.builder()
    .name("assistant")
    .model("dashscope:qwen-plus") // Resolved internally by ModelRegistry.resolve(modelId)
    .build();
```

```java
ReActAgent agent = ReActAgent.builder()
    .name("assistant")
    .model("qwen-plus") // Resolved internally by ModelRegistry.resolve(modelId)
    .build();
```

## Explicit builder

Use the builder when you need DashScope-specific options such as endpoint type, thinking, search, encryption:

```java
import io.agentscope.extensions.model.dashscope.DashScopeChatModel;

DashScopeChatModel model = DashScopeChatModel.builder()
    .apiKey(System.getenv("DASHSCOPE_API_KEY"))
    .modelName("qwen-plus")
    .stream(true)
    .build();
```

## Documents

DashScope reads documents two ways, matched by the source of the `DocumentBlock`:

- **File id** (`qwen-long`, `qwen-doc-turbo`): upload the file through the OpenAI-compatible
  `files` endpoint and pass a `FileIdSource` with provider `"dashscope"`. The formatter injects a
  `fileid://` system message before the turn that references the document. Historical turns keep
  their references: a long conversation re-sends one system message per past file-id document on
  every request.
- **Public URL** (`qwen-doc-turbo`): pass a `URLSource` pointing at a publicly reachable file.
  Up to 10 URL entries are accepted per request and they travel as `doc_url` content parts inside
  the user turn itself. This limit includes documents resent with the conversation history;
  repeated URLs count again. Compaction can replace old documents with text references, but it
  is not guaranteed to run before the limit is reached.

```java
Msg user = Msg.builder()
    .role(MsgRole.USER)
    .content(List.of(
        TextBlock.builder().text("Summarise this").build(),
        DocumentBlock.builder()
            .source(URLSource.builder().url("https://example.com/report.pdf").build())
            .name("report.pdf")
            .build()))
    .build();
```

Base64 documents have no DashScope channel and are rejected with an error; upload the file or
host it at a public URL instead. Note that `doc_url` parts are only accepted by the
text-generation endpoint: vision models (routed to the multimodal endpoint) reject URL documents
and need a per-model alternative - check the model's own input-capability table (file id, or no
document input at all) instead of assuming the file-id path works everywhere.

The parsing strategy can be configured per document through the block metadata:

```java
DocumentBlock.builder()
    .source(URLSource.builder().url("https://example.com/report.pdf").build())
    .metadata(Map.of("file_parsing_strategy", "text_and_images")) // auto | text_only | text_and_images
    .build();
```

## PPT generation (qwen-doc-turbo)

`qwen-doc-turbo` can generate a PowerPoint file from the document in the conversation. The skill
requires streaming and travels through additional body params, which the DashScope HTTP client
merges into the request `parameters`. The example below configures it as a model default via
`defaultOptions`, so every call of that model carries it; for a one-off call, pass the same
`GenerateOptions` through your model's per-call options entry point instead:

```java
import io.agentscope.extensions.model.dashscope.dto.DashScopeSkill;

DashScopeChatModel model = DashScopeChatModel.builder()
    .apiKey(System.getenv("DASHSCOPE_API_KEY"))
    .modelName("qwen-doc-turbo")
    .stream(true) // the skill is only supported in stream mode
    .defaultOptions(
        GenerateOptions.builder()
            .additionalBodyParam("skill", List.of(DashScopeSkill.pptCreative()))
            .build())
    .build();

ReActAgent agent = ReActAgent.builder().name("DocumentAssistant").model(model).build();
agent.call(user).block();
```

Template mode (`DashScopeSkill.pptTemplate(...)`) reads the document as plain text carried in the
system message body — a `fileid://` reference or a `doc_url` part is not consumed there. Per the
official input-capability table, generating a PPT from a file ID or from a `doc_url` document
requires creative mode (`pptCreative()`), which is what the example above uses. The outline and
page content stream back through thinking blocks (`reasoning_content`); the final PPT download URL
arrives as the text content of the last response. That reply carries the metadata key `dashscope.skill` (value
`ppt`, exposed as `DashScopeSkill.RESPONSE_METADATA_KEY`), so consumers can identify skill output.
The metadata contains the skill type; the download URL remains in the text content.

## Spring Boot

Spring Boot applications can use the DashScope starter:

```xml
<dependency>
    <groupId>io.agentscope</groupId>
    <artifactId>agentscope-dashscope-spring-boot-starter</artifactId>
    <version>${agentscope.version}</version>
</dependency>
```

Full builder options, formatters, credentials, and registry context details are covered in [Model](/v2/en/docs/building-blocks/model).
