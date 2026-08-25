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

## Prompt 缓存

### 隐式缓存

Gemini 2.5 及更新模型由服务端默认启用隐式缓存，请求无需增加额外字段。设置 `GenerateOptions.cacheControl(true)` 不会创建 Gemini 缓存资源，设为 `false` 也不会关闭服务端管理的隐式缓存。

同样，为 `Msg` 添加 `MessageMetadataKeys.CACHE_CONTROL` 不会创建或管理 Gemini `cachedContents` 资源。Gemini 显式缓存拥有独立的服务端资源生命周期。

### 引用显式缓存资源

先使用 Google Gen AI SDK 创建和管理缓存资源，再通过 `GenerateOptions` 传入它的资源名称：

```java
GenerateOptions options = GenerateOptions.builder()
    .additionalBodyParam("cachedContent", cache.name().orElseThrow())
    .build();

model.stream(dynamicMessages, tools, options);
```

资源名称会原样传给 `GenerateContentConfig.cachedContent(...)`。必须使用由相同模型创建且未过期的缓存；对于 Vertex AI，project 和 location 也必须一致。

缓存内容是当前请求的前缀。`dynamicMessages` 中只传递动态后缀，不要重复传入已写入缓存的消息。如果请求需要 system instruction、工具声明或 tool config，应在创建缓存资源时写入。Gemini 不允许在引用缓存的 `generateContent` 请求中重复发送这些字段。AgentScope 仍可以接收工具 schema 用于本地工具执行，但它必须与缓存资源中的声明一致。

缓存的创建、TTL 更新、查询和删除由 Google SDK 管理。AgentScope 在生成请求中只引用用户传入的资源名称。

### 缓存用量

Gemini 的缓存用量会按以下规则归一化到 `ChatUsage`：

- `inputTokens` 是完整的有效输入 token 数，已包含缓存 token。
- `cachedTokens` 是 Gemini `cachedContentTokenCount` 报告的缓存子集。
- `cacheCreationInputTokens` 在 `generateContent` 中为 `0`；缓存创建是独立的资源操作。

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
