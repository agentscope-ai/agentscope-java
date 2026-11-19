---
title: OpenAI
en_link: /v2/en/integration/model/openai
---

`agentscope-extensions-model-openai` 接入 OpenAI Chat Completions 风格的模型。OpenAI 兼容端点也使用这个适配模块，例如 DeepSeek、GLM、Kimi、MiniMax 等遵循 OpenAI API 载荷格式的服务。

## 添加依赖

```xml
<dependency>
    <groupId>io.agentscope</groupId>
    <artifactId>agentscope-extensions-model-openai</artifactId>
    <version>${agentscope.version}</version>
</dependency>
```

## ModelRegistry

设置 `OPENAI_API_KEY` 后，使用 `openai:<model>` 字符串 id：

```java
ReActAgent agent = ReActAgent.builder()
    .name("assistant")
    .model("openai:gpt-4.1-mini") // 底层由 ModelRegistry.resolve(modelId) 解析
    .build();
```

## 显式 builder

需要自定义 endpoint、formatter、transport 或生成参数时，使用 builder：

```java
import io.agentscope.extensions.model.openai.OpenAIChatModel;

OpenAIChatModel model = OpenAIChatModel.builder()
    .apiKey(System.getenv("OPENAI_API_KEY"))
    .modelName("gpt-4.1-mini")
    .stream(true)
    .build();
```

## 推理历史

Formatter 会在会话历史中保留推理详情块，包括加密数据。生成下一轮请求时，每条详情会复制到请求对象，并省略可选的 `index` 字段，避免严格的 OpenAI 兼容端点将其判为不允许回传的流式字段。原始响应与持久化历史仍保留 index；请求中的 `id`、`type`、`data`、`text`、`format` 及块顺序保持不变。

## Spring Boot

Spring Boot 应用可以使用 OpenAI starter：

```xml
<dependency>
    <groupId>io.agentscope</groupId>
    <artifactId>agentscope-openai-spring-boot-starter</artifactId>
    <version>${agentscope.version}</version>
</dependency>
```

完整 builder 选项、formatter、credential 和 registry context 细节见 [模型](/v2/zh/docs/building-blocks/model)。
