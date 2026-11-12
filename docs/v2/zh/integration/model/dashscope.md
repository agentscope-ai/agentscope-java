---
title: DashScope
en_link: /v2/en/integration/model/dashscope
---

`agentscope-extensions-model-dashscope` 接入阿里云 DashScope Qwen Model，包括多模态和推理能力的 Qwen 模型。

## 添加依赖

```xml
<dependency>
    <groupId>io.agentscope</groupId>
    <artifactId>agentscope-extensions-model-dashscope</artifactId>
    <version>${agentscope.version}</version>
</dependency>
```

## ModelRegistry

设置 `DASHSCOPE_API_KEY` 后，可以使用 `dashscope:<model>`，也可以使用 Qwen 短名：

```java
ReActAgent agent = ReActAgent.builder()
    .name("assistant")
    .model("dashscope:qwen-plus") // 底层由 ModelRegistry.resolve(modelId) 解析
    .build();
```

```java
ReActAgent agent = ReActAgent.builder()
    .name("assistant")
    .model("qwen-plus") // 底层由 ModelRegistry.resolve(modelId) 解析
    .build();
```

## 显式 builder

需要 DashScope 专属配置时使用 builder，例如 endpoint type、thinking、search、encryption：

```java
import io.agentscope.extensions.model.dashscope.DashScopeChatModel;

DashScopeChatModel model = DashScopeChatModel.builder()
    .apiKey(System.getenv("DASHSCOPE_API_KEY"))
    .modelName("qwen-plus")
    .stream(true)
    .build();
```

## 文档输入

DashScope 通过 `DocumentBlock` 的 source 类型区分两种文档通道：

- **文件 ID**（`qwen-long`、`qwen-doc-turbo`）：先通过 OpenAI 兼容的 `files` 接口上传文件，
  再以 provider 为 `"dashscope"` 的 `FileIdSource` 传入。formatter 会在引用该文档的轮次之前
  注入一条 `fileid://` system message。历史轮次的引用会保留：长会话中每次请求都会为历史上
  每个文件 ID 文档重发一条 system message。
- **公网 URL**（`qwen-doc-turbo`）：传入指向公网可访问文件的 `URLSource`。单次请求最多 10 条
  URL，以 `doc_url` 内容块的形式随用户消息本身发送。该上限包括随历史重新发送的文档，同一 URL
  重复出现也重复计数。压缩可以把旧文档替换为文本引用，但不保证在达到上限前触发。

```java
Msg user = Msg.builder()
    .role(MsgRole.USER)
    .content(List.of(
        TextBlock.builder().text("帮我总结这份文档").build(),
        DocumentBlock.builder()
            .source(URLSource.builder().url("https://example.com/report.pdf").build())
            .name("report.pdf")
            .build()))
    .build();
```

Base64 文档在 DashScope 上没有可用通道，会直接报错；请改为上传文件或使用公网 URL。注意
`doc_url` 仅 text-generation 端点支持：视觉模型（路由到多模态端点）会拒绝 URL 文档，替代方案
因模型而异——请查阅具体模型的输入能力表（支持文件 ID，或完全不支持文档输入），不要默认文件 ID
通道处处可用。

每个文档的解析方式可以通过块级
metadata 配置：

```java
DocumentBlock.builder()
    .source(URLSource.builder().url("https://example.com/report.pdf").build())
    .metadata(Map.of("file_parsing_strategy", "text_and_images")) // auto | text_only | text_and_images
    .build();
```

## PPT 生成（qwen-doc-turbo）

`qwen-doc-turbo` 可以基于对话中的文档生成 PPT。该 skill 要求流式调用，通过 additional body
params 传入（DashScope HTTP 客户端会将其合并进请求的 `parameters`）。下面的示例通过
`defaultOptions` 将其配置为模型默认值，该模型的每次调用都会携带；如需按次传入，请在模型的
per-call 选项入口传入同样的 `GenerateOptions`：

```java
import io.agentscope.extensions.model.dashscope.dto.DashScopeSkill;

DashScopeChatModel model = DashScopeChatModel.builder()
    .apiKey(System.getenv("DASHSCOPE_API_KEY"))
    .modelName("qwen-doc-turbo")
    .stream(true) // skill 仅在流式模式下可用
    .defaultOptions(
        GenerateOptions.builder()
            .additionalBodyParam("skill", List.of(DashScopeSkill.pptCreative()))
            .build())
    .build();

ReActAgent agent = ReActAgent.builder().name("DocumentAssistant").model(model).build();
agent.call(user).block();
```

模板模式（`DashScopeSkill.pptTemplate(...)`）以 system message 正文携带的纯文本作为文档——
`fileid://` 引用与 `doc_url` part 在该模式下不会被消费。按官方输入能力表，从文件 ID 或
`doc_url` 文档生成 PPT 必须使用创意模式（`pptCreative()`），上面的示例即采用该模式。
大纲与逐页内容通过 thinking block（`reasoning_content`）流出，最终的 PPT 下载链接出现在最后
一条响应的文本 content 中。该回复的 metadata 会带上 `dashscope.skill` 键（值为 `ppt`，即
`DashScopeSkill.RESPONSE_METADATA_KEY`），消费方可据此识别 skill 输出。metadata 只包含 skill 类型，
下载链接仍在文本 content 中。

## Spring Boot

Spring Boot 应用可以使用 DashScope starter：

```xml
<dependency>
    <groupId>io.agentscope</groupId>
    <artifactId>agentscope-dashscope-spring-boot-starter</artifactId>
    <version>${agentscope.version}</version>
</dependency>
```

完整 builder 选项、formatter、credential 和 registry context 细节见 [模型](/v2/zh/docs/building-blocks/model)。
