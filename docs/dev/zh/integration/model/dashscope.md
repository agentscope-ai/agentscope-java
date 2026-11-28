---
title: DashScope
en_link: /dev/en/integration/model/dashscope
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

## 流式响应

DashScope 的 SSE 解析器会跳过格式错误的 JSON 和 JSON `null` 帧，使这些帧不会覆盖后续有效响应。服务端错误响应仍会以 `DashScopeHttpException` 终止流，并保留包含请求 ID（如果存在）的原始响应体；传输失败也仍作为错误传播，不会被视为空输出。跳过无效帧不能恢复该帧中缺失的内容。

## Spring Boot

Spring Boot 应用可以使用 DashScope starter：

```xml
<dependency>
    <groupId>io.agentscope</groupId>
    <artifactId>agentscope-dashscope-spring-boot-starter</artifactId>
    <version>${agentscope.version}</version>
</dependency>
```

完整 builder 选项、formatter、credential 和 registry context 细节见 [模型](/dev/zh/docs/building-blocks/model)。
