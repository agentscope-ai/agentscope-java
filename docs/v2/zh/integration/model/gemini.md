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

## Thinking 配置

Gemini 的 thinking 能力通过 `GenerateOptions` 配置：

```java
import io.agentscope.core.model.GenerateOptions;

GenerateOptions thinkingOptions = GenerateOptions.builder()
    .thinkingLevel("high")
    .includeThoughts(true)
    .build();
```

- `thinkingBudget(Integer)` 为支持 token 预算的模型设置 thinking 预算。
- `thinkingLevel(String)` 设置模型支持的等级，例如 `"minimal"`、`"low"`、`"medium"` 或 `"high"`。
- `includeThoughts(Boolean)` 独立控制响应中是否包含 thoughts。

请根据所选模型使用 `thinkingBudget` 或 `thinkingLevel`。为保持向后兼容，仅设置 `thinkingBudget` 时仍会同时启用 `includeThoughts`；显式设置的 `includeThoughts` 始终优先。

Chat formatter 会自动回放历史 assistant `ThinkingBlock`，并保留 thinking、text 和 tool-call Part 上的 Gemini `thoughtSignature`，即使消息经过 JSON 序列化也不会丢失。存储或转换对话历史时，请保留 content block metadata。

持久化后无法恢复的签名（例如损坏或非 Base64 的值）不会被发送：框架会记录警告并丢弃该签名，请求继续执行。不同块类型的后果不同：thinking Part 会优雅地降级为普通文本 Part；而 function call Part 依赖签名，恢复持久化会话后，Gemini 3 可能会拒绝丢失 `thoughtSignature` 的 function call（通常返回 400 错误）。此时应移除或重新生成受影响的对话轮次，而不是原样重试。

完整的单次请求链路见[Gemini 完整请求流程](/v2/zh/integration/model/gemini-request-flow)。

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
