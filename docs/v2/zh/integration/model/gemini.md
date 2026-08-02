# Gemini 模型

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
import io.agentscope.extensions.model.gemini.GeminiChatModel;
import io.agentscope.extensions.model.gemini.tool.GeminiServerTool;
import java.util.List;

GeminiChatModel model = GeminiChatModel.builder()
    .apiKey(System.getenv("GEMINI_API_KEY"))
    .modelName("gemini-2.0-flash")
    .serverTools(List.of(
        GeminiServerTool.googleSearch()
            .param("excludeDomains", List.of("example.com"))
            .build(),
        GeminiServerTool.urlContext().build()
    ))
    .build();
```

| 工具 | 配置方式和支持的参数 |
| --- | --- |
| Google Search | `GeminiServerTool.googleSearch()`；`searchTypes`、`blockingConfidence`、`excludeDomains`、`timeRangeFilter` |
| Google Maps | `GeminiServerTool.googleMap()`；`authConfig`、`enableWidget` |
| URL Context | `GeminiServerTool.urlContext()`；不支持参数 |
| Code Execution | `GeminiServerTool.builder().type(GeminiServerTool.CODE_EXECUTION)`；不支持参数 |

配置至少一个服务端工具后，AgentScope 会自动启用 Gemini 服务端调用上下文，并在对话历史中保留返回的
工具调用和结果。服务端工具与本地 function tools 相互独立，因此可以在同一次请求中同时提供。

## Spring Boot

Spring Boot 应用可以使用 Gemini starter：

```xml
<dependency>
    <groupId>io.agentscope</groupId>
    <artifactId>agentscope-gemini-spring-boot-starter</artifactId>
    <version>${agentscope.version}</version>
</dependency>
```

完整 builder 选项、formatter、credential 和 registry context 细节见 [模型](../../docs/building-blocks/model.md)。
