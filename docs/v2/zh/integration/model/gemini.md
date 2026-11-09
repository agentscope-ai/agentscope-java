---
title: Gemini
en_link: /v2/en/integration/model/gemini
---

`agentscope-extensions-model-gemini` 接入 Google Gemini 模型。它支持 Gemini API，也可以通过显式配置走 Vertex AI 路径。

## 添加依赖

```xml
<dependency>
    <groupId>io.agentscope</groupId>
    <artifactId>agentscope-extensions-model-gemini</artifactId>
    <version>${agentscope.version}</version>
</dependency>
```

## ModelRegistry

设置 `GEMINI_API_KEY` 后，使用 `gemini:<model>` 字符串 id：

```java
ReActAgent agent = ReActAgent.builder()
    .name("assistant")
    .model("gemini:gemini-2.0-flash") // 底层由 ModelRegistry.resolve(modelId) 解析
    .build();
```

## 显式 builder

需要自定义 API 设置、Vertex AI credentials、formatter、transport 或生成参数时，使用 builder：

```java
import io.agentscope.extensions.model.gemini.GeminiChatModel;

GeminiChatModel model = GeminiChatModel.builder()
    .apiKey(System.getenv("GEMINI_API_KEY"))
    .modelName("gemini-2.0-flash")
    .streamEnabled(true)
    .build();
```

## 服务端工具

Gemini 内置工具由模型服务商执行，不会通过 AgentScope 本地 Toolkit 执行。使用显式 model
builder 配置这些工具：

```java
import io.agentscope.extensions.model.gemini.tool.GeminiServerTool;
import com.google.genai.types.GoogleSearch;
import com.google.genai.types.Tool;
import com.google.genai.types.UrlContext;

GeminiChatModel model = GeminiChatModel.builder()
    .apiKey(System.getenv("GEMINI_API_KEY"))
    .modelName("gemini-3.8-flash")
    .serverTools(List.of(
        GeminiServerTool.of(Tool.builder()
            .googleSearch(GoogleSearch.builder()
                .excludeDomains(List.of("example.com"))
                .build())
            .build()),
        GeminiServerTool.of(Tool.builder()
            .urlContext(UrlContext.builder())
            .build())
    ))
    .build();
```

`GeminiServerTool.of(...)` 可用于包装 Google GenAI SDK `Tool` builder 构建的内置服务端工具定义，
例如 Google Search、Google Maps、URL Context、Code Execution、File Search 和 Enterprise Web Search。

配置至少一个服务端工具后，AgentScope 会自动启用 Gemini 服务端调用上下文，并在对话历史中保留返回的
工具调用和结果。服务端工具与本地 function tools 相互独立，因此可以在同一次请求中同时提供。
不要用它包装自定义 function tools；`GeminiServerTool.of(...)` 会拒绝这类定义。

## 文档输入

Gemini 原生支持文档输入。用户消息中的 `DocumentBlock` 会被转换为 `inlineData` part（来自
`Base64Source` 的字节或可读取的 `URLSource`）；当 source 是 provider 为 `gemini` 的
`FileIdSource` 时，会转换为 `fileData` part——`files/abc123` 这类 Files API 简名会自动展开为
`fileData` 所需的完整 URI。

API 只能读取 `generateContent` 参考中列出的文档类型——`application/pdf`、`application/json`、
纯文本与代码格式（`text/plain`、`text/html`、`text/css`、`text/markdown`、`text/csv`、
`text/xml`、`text/rtf`、`text/javascript`、`text/x-typescript`、`text/x-python` 及对应的
`application/x-*` 代码类型）。声明为其他 MIME 类型的文档（例如 `.docx`）会被降级为文本占位符，
而不是发给 API——否则整个请求都会被拒绝。

## Spring Boot

Spring Boot 应用可以使用 Gemini starter：

```xml
<dependency>
    <groupId>io.agentscope</groupId>
    <artifactId>agentscope-gemini-spring-boot-starter</artifactId>
    <version>${agentscope.version}</version>
</dependency>
```

完整 builder 选项、formatter、credential 和 registry context 细节见 [模型](/v2/zh/docs/building-blocks/model)。
