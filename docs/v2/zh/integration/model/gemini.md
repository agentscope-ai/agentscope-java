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
import io.agentscope.extensions.model.gemini.GeminiChatModel;
import io.agentscope.extensions.model.gemini.tool.GeminiServerTool;
import com.google.genai.types.GoogleSearch;
import com.google.genai.types.Tool;
import com.google.genai.types.UrlContext;
import java.util.List;

GeminiChatModel model = GeminiChatModel.builder()
    .apiKey(System.getenv("GEMINI_API_KEY"))
    .modelName("gemini-2.0-flash")
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

凡是 Google GenAI SDK `Tool` builder 支持的服务端工具，都可以用 `GeminiServerTool.of(...)`
包装。常见示例包括 Google Search、Google Maps、URL Context、Code Execution、File Search 和
Enterprise Web Search。

配置至少一个服务端工具后，AgentScope 会自动启用 Gemini 服务端调用上下文，并在对话历史中保留返回的
工具调用和结果。服务端工具与本地 function tools 相互独立，因此可以在同一次请求中同时提供。
两者的逻辑名称会作为统一命名空间检查，`ToolChoice.None` 会从请求中移除 Gemini 服务端工具。
Gemini 无法强制指定某个服务端工具，也无法只要求服务端工具必然调用；它也没有关闭并行工具调用的
开关。遇到这些不支持的组合时会快速失败，而不是静默忽略。

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
