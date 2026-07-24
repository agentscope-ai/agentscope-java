# Anthropic 模型

`agentscope-extensions-model-anthropic` 接入 Anthropic Claude Model，并提供 Anthropic 专属 formatter 和请求 DTO 支持。

## 添加依赖

```xml
<dependency>
    <groupId>io.agentscope</groupId>
    <artifactId>agentscope-extensions-model-anthropic</artifactId>
    <version>${agentscope.version}</version>
</dependency>
```

## ModelRegistry

设置 `ANTHROPIC_API_KEY` 后，使用 `anthropic:<model>` 字符串 id：

```java
ReActAgent agent = ReActAgent.builder()
    .name("assistant")
    .model("anthropic:claude-sonnet-4.5") // 底层由 ModelRegistry.resolve(modelId) 解析
    .build();
```

## 显式 builder

需要自定义 endpoint、formatter、transport、prompt caching、thinking 或生成参数时，使用 builder：

```java
import io.agentscope.extensions.model.anthropic.AnthropicChatModel;

AnthropicChatModel model = AnthropicChatModel.builder()
    .apiKey(System.getenv("ANTHROPIC_API_KEY"))
    .modelName("claude-sonnet-4.5")
    .stream(true)
    .build();
```

## PDF 文档与引用

PDF `DataBlock` 会转换为 Anthropic document block。支持 Base64、本地文件路径或 `file://` URL，
以及远程 HTTP(S) URL；本地 PDF 会在发送请求前编码为 Base64。

```java
import io.agentscope.core.message.DataBlock;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.message.URLSource;
import io.agentscope.core.model.GenerateOptions;
import java.util.List;

DataBlock document = DataBlock.builder()
    .name("guide.pdf")
    .source(URLSource.builder()
        .url("file:///absolute/path/to/guide.pdf")
        .mimeType("application/pdf")
        .build())
    .build();

Msg request = Msg.builder()
    .role(MsgRole.USER)
    .content(document, TextBlock.builder().text("请总结这个文档。").build())
    .build();

GenerateOptions options = GenerateOptions.builder()
    .citationsEnabled(true)
    .build();

model.stream(List.of(request), List.of(), options);
```

Anthropic 要求一次请求中的 document 必须全部开启 citations 或全部关闭，因此
`citationsEnabled` 是请求级选项，并会应用到所有 PDF document。返回的引用可以通过
`TextBlock.getCitations()` 获取。带引用的响应可能包含多个文本块，以保证每条引用仍然与其
支持的具体文本片段关联。

当前仅支持 PDF document block，暂不支持 plain-text document、custom-content document 和
Anthropic Files API 的 `file_id` source。

## Spring Boot

Spring Boot 应用可以使用 Anthropic starter：

```xml
<dependency>
    <groupId>io.agentscope</groupId>
    <artifactId>agentscope-anthropic-spring-boot-starter</artifactId>
    <version>${agentscope.version}</version>
</dependency>
```

完整 builder 选项、formatter、credential 和 registry context 细节见 [模型](../../docs/building-blocks/model.md)。
